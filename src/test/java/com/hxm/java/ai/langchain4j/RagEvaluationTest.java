package com.hxm.java.ai.langchain4j;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RAG 检索离线评估：不跑 LLM，只跑 ContentRetriever，
 * 用「标准答案相关文档」量化召回率，对比不同检索配置。
 *
 * <p>召回率定义：对每个问题，先根据评估集里的「答案片段」在语料库中找出所有相关文档（ground truth），
 * 再统计检索返回的 Top-K 文档覆盖了多少相关文档。</p>
 */
@SpringBootTest(classes = XiaozhiApp.class)
public class RagEvaluationTest {

    @Autowired
    @Qualifier("vectorRetriever")
    private ContentRetriever vectorRetriever;

    @Autowired
    @Qualifier("hybridContentRetriever")
    private ContentRetriever hybridContentRetriever;

    @Autowired
    @Qualifier("pgVectorDataSource")
    private DataSource pgVectorDataSource;

    private static final int TOP_K = 5;

    @Test
    public void compareRecall() throws Exception {
        List<EvalItem> items = loadEvalSet();
        Map<String, String> corpus = loadCorpus();

        if (corpus.isEmpty()) {
            System.out.println("[RagEval] 语料库为空，请先运行 LLMTest#testUploadKnowledgeLibrary 灌入知识库");
            return;
        }

        Metrics vector = evaluate(vectorRetriever, items, corpus);
        Metrics hybrid = evaluate(hybridContentRetriever, items, corpus);

        System.out.println();
        System.out.println("==================== RAG 检索召回率对比 ====================");
        System.out.printf("%-22s %10s %10s %10s %10s %10s%n",
                "配置", "Recall@1", "Recall@3", "Recall@5", "Hit@5", "MRR");
        System.out.println("-----------------------------------------------------------");
        printRow("纯向量", vector);
        printRow("混合(向量+关键词)", hybrid);
        System.out.println("===========================================================");
        System.out.println("评估集条目数=" + items.size() + "，Top-K=" + TOP_K);
        System.out.println("Recall@k = 检索命中的相关文档数 / 该问题相关文档总数，越大越好");
    }

    private void printRow(String name, Metrics m) {
        System.out.printf("%-22s %10.3f %10.3f %10.3f %10.3f %10.3f%n",
                name, m.recall1, m.recall3, m.recall5, m.hit5, m.mrr);
    }

    private Metrics evaluate(ContentRetriever retriever, List<EvalItem> items, Map<String, String> corpus) {
        double r1 = 0, r3 = 0, r5 = 0, hit = 0, mrr = 0;
        for (EvalItem item : items) {
            List<String> ids = retriever.retrieve(Query.from(item.query)).stream()
                    .limit(TOP_K)
                    .map(this::extractId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            Set<String> relevant = relevantIds(item, corpus);
            if (relevant.isEmpty()) {
                continue;
            }

            r1 += recallAt(ids, relevant, 1);
            r3 += recallAt(ids, relevant, 3);
            r5 += recallAt(ids, relevant, 5);
            hit += ids.stream().anyMatch(relevant::contains) ? 1 : 0;
            mrr += mrr(ids, relevant);
        }
        int n = items.size();
        return new Metrics(r1 / n, r3 / n, r5 / n, hit / n, mrr / n);
    }

    /** 标准答案相关文档：语料中包含该问题任意「答案片段」的 chunk 集合 */
    private Set<String> relevantIds(EvalItem item, Map<String, String> corpus) {
        Set<String> relevant = new HashSet<>();
        for (Map.Entry<String, String> e : corpus.entrySet()) {
            String text = e.getValue();
            for (String exp : item.expected) {
                if (text != null && text.contains(exp)) {
                    relevant.add(e.getKey());
                    break;
                }
            }
        }
        return relevant;
    }

    private double recallAt(List<String> ids, Set<String> relevant, int k) {
        Set<String> top = new HashSet<>(ids.subList(0, Math.min(k, ids.size())));
        top.retainAll(relevant);
        return (double) top.size() / relevant.size();
    }

    private double mrr(List<String> ids, Set<String> relevant) {
        for (int i = 0; i < ids.size(); i++) {
            if (relevant.contains(ids.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0;
    }

    private String extractId(Content content) {
        Object id = content.metadata().get(ContentMetadata.EMBEDDING_ID);
        return id == null ? null : id.toString();
    }

    private List<EvalItem> loadEvalSet() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/rag-eval.json")) {
            if (in == null) {
                throw new IllegalStateException("未找到 src/test/resources/rag-eval.json");
            }
            return new ObjectMapper().readValue(in, new TypeReference<List<EvalItem>>() {});
        }
    }

    private Map<String, String> loadCorpus() {
        JdbcTemplate jdbc = new JdbcTemplate(pgVectorDataSource);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT embedding_id, text FROM medical_documents");
        Map<String, String> corpus = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object id = row.get("embedding_id");
            Object text = row.get("text");
            corpus.put(String.valueOf(id), text == null ? "" : text.toString());
        }
        return corpus;
    }

    static class EvalItem {
        public String query;
        public List<String> expected;
    }

    static class Metrics {
        final double recall1, recall3, recall5, hit5, mrr;

        Metrics(double r1, double r3, double r5, double h5, double m) {
            recall1 = r1;
            recall3 = r3;
            recall5 = r5;
            hit5 = h5;
            mrr = m;
        }
    }
}
