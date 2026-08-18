package com.hxm.java.ai.langchain4j.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 硅基流动 ReRank 打分模型（BAAI/bge-reranker-v2-m3，中英跨语言重排序）。
 * 实现 langchain4j 的 {@link ScoringModel}，供「粗召回 → 精排」两阶段检索使用。
 *
 * <p>接口：POST /v1/rerank，Cohere 风格，返回按相关度排序的 {@code results[index, relevance_score]}。</p>
 */
public class SiliconFlowScoringModel implements ScoringModel {

    private static final String URL = "https://api.siliconflow.cn/v1/rerank";
    private static final String MODEL = "BAAI/bge-reranker-v2-m3";

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public SiliconFlowScoringModel(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        try {
            List<String> documents = segments.stream()
                    .map(TextSegment::text)
                    .collect(Collectors.toList());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", MODEL);
            body.put("query", query);
            body.put("documents", documents);
            body.put("top_n", documents.size());
            body.put("return_documents", false);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("ReRank 调用失败，HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            double[] scores = new double[documents.size()];
            for (JsonNode r : root.path("results")) {
                int idx = r.path("index").asInt();
                double score = r.path("relevance_score").asDouble();
                if (idx >= 0 && idx < scores.length) {
                    scores[idx] = score;
                }
            }

            List<Double> result = new ArrayList<>(documents.size());
            for (double s : scores) {
                result.add(s);
            }
            return Response.from(result);
        } catch (Exception e) {
            throw new RuntimeException("硅基流动 ReRank 调用异常", e);
        }
    }
}
