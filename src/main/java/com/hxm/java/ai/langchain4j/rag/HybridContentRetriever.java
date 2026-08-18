package com.hxm.java.ai.langchain4j.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.aggregator.ReciprocalRankFuser;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 两阶段混合检索器：粗召回（向量 + 关键词 RRF 融合，各取 20 条）→ 精排（ReRank 打分重排取前 5）。
 *
 * <p>粗召回追求「宁多勿漏」，向量检索与关键词检索互补扩大候选集；精排用交叉编码器
 * （bge-reranker）对候选逐条打分，把真正相关的内容排到前面，抑制粗召回带来的噪声。</p>
 *
 * <p>关键词检索用「字符二元组重叠」而非 pg_trgm 的 {@code similarity()}：后者按空格分词、
 * 面向英文设计，中文无空格时短 query 对长文档的相似度几乎恒为 0，等于噪声。</p>
 */
@Component
public class HybridContentRetriever implements ContentRetriever {

    // 向量检索器（内部负责 query 向量化 + EmbeddingStore 相似度检索）
    private final ContentRetriever vectorRetriever;

    // 关键词检索（复用 pgvector 同一数据库的 JdbcTemplate）
    private final JdbcTemplate jdbcTemplate;

    // ReRank 精排打分模型（交叉编码器）
    private final ScoringModel scoringModel;

    private final String table;

    private static final int COARSE_RESULTS = 20;   // 粗召回条数（RRF 融合后）
    private static final int KEYWORD_RESULTS = 20;  // 关键词粗召回条数
    private static final int RERANK_RESULTS = 5;    // 精排后返回条数

    // 查询改写器对非医疗问题（问候、身份、闲聊等）输出的标记，遇到则跳过检索
    private static final String NO_RETRIEVAL = "【无需检索】";

    public HybridContentRetriever(@Qualifier("vectorRetriever") ContentRetriever vectorRetriever,
                                  @Qualifier("pgVectorDataSource") DataSource dataSource,
                                  ScoringModel scoringModel) {
        this.vectorRetriever = vectorRetriever;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.scoringModel = scoringModel;
        this.table = "medical_documents";
    }

    @Override
    public List<Content> retrieve(Query query) {
        if (query.text() == null) {
            return List.of();
        }
        // 非医疗问题（问候、身份等）：返回哨兵内容，供注入器识别并原样放行，
        // 避免被误判为「检索为空」而触发「说不知道」的兜底
        if (query.text().contains(NO_RETRIEVAL)) {
            return List.of(Content.from(NO_RETRIEVAL));
        }

        // 1. 粗召回：向量语义检索 + 关键词检索，RRF 融合
        List<Content> vectorResults = vectorRetriever.retrieve(query);
        List<Content> keywordResults = keywordSearch(query.text());
        List<Content> fused = ReciprocalRankFuser.fuse(Arrays.asList(vectorResults, keywordResults));
        List<Content> coarse = fused.subList(0, Math.min(fused.size(), COARSE_RESULTS));

        // 2. 精排：ReRank 对候选逐条打分，重排取前 K
        return reRank(query.text(), coarse);
    }

    private List<Content> reRank(String queryText, List<Content> candidates) {
        if (candidates.size() <= RERANK_RESULTS) {
            return candidates;
        }
        try {
            List<TextSegment> segments = candidates.stream()
                    .map(Content::textSegment)
                    .collect(Collectors.toList());
            List<Double> scores = scoringModel.scoreAll(segments, queryText).content();

            // 按得分降序取前 RERANK_RESULTS，保留原 Content 对象（含 embedding_id 等 metadata）
            return IntStream.range(0, candidates.size())
                    .boxed()
                    .sorted(Comparator.comparingDouble((Integer i) -> scores.get(i)).reversed())
                    .limit(RERANK_RESULTS)
                    .map(candidates::get)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            // 精排失败兜底：退回粗召回顺序，不影响主流程
            return candidates.subList(0, Math.min(candidates.size(), RERANK_RESULTS));
        }
    }

    private List<Content> keywordSearch(String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }
        List<String> grams = distinctBigrams(queryText);
        if (grams.isEmpty()) {
            return List.of();
        }
        // 命中最少二元组数：query 只有 1 个二元组（极短）时降到 1，否则至少 2 个以抑制噪声
        int minHits = grams.size() == 1 ? 1 : 2;

        String sql = "SELECT embedding_id, text FROM " + table + " WHERE text IS NOT NULL";
        List<Object[]> rows = jdbcTemplate.query(sql, (rs, rowNum) ->
                new Object[]{ rs.getString("embedding_id"), rs.getString("text") });

        record Scored(String id, String text, int hits) {}

        List<Scored> matched = new ArrayList<>();
        for (Object[] row : rows) {
            String id = (String) row[0];
            String text = (String) row[1];
            int hits = 0;
            for (String g : grams) {
                if (text.contains(g)) {
                    hits++;
                }
            }
            if (hits >= minHits) {
                matched.add(new Scored(id, text, hits));
            }
        }
        matched.sort(Comparator.comparingInt((Scored s) -> s.hits()).reversed());

        List<Content> results = new ArrayList<>();
        for (Scored s : matched) {
            if (results.size() >= KEYWORD_RESULTS) {
                break;
            }
            // 与向量检索器对齐：把文档 id 放进 Content 的 EMBEDDING_ID 元数据，供评估召回率使用
            if (s.id() == null) {
                results.add(Content.from(s.text()));
            } else {
                results.add(Content.from(TextSegment.from(s.text()), Map.of(ContentMetadata.EMBEDDING_ID, s.id())));
            }
        }
        return results;
    }

    /** 去掉空白和标点后，取所有连续两个字符组成的二元组并去重 */
    private List<String> distinctBigrams(String text) {
        String cleaned = text.replaceAll("[\\s\\p{P}]", "");
        Set<String> set = new LinkedHashSet<>();
        for (int i = 0; i + 1 < cleaned.length(); i++) {
            set.add(cleaned.substring(i, i + 2));
        }
        return new ArrayList<>(set);
    }
}
