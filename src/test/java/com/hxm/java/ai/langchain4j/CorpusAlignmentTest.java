package com.hxm.java.ai.langchain4j;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 对齐评估集与语料库：dump 出 medical_documents 的全部 chunk，
 * 并逐个检查评估集「答案片段」是否原样存在于语料中（与召回率评估的 relevantIds 判定口径一致）。
 * 纯 JDBC，不启动 Spring 上下文。
 */
public class CorpusAlignmentTest {

    private static final String URL = "jdbc:postgresql://192.168.116.130:5432/rag_db";

    public static void main(String[] args) throws Exception {
        new CorpusAlignmentTest().run();
    }

    @Test
    public void listIndexes() throws Exception {
        Class.forName("org.postgresql.Driver");
        try (Connection c = DriverManager.getConnection(URL, "postgres", "123456");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'medical_documents'")) {
            while (rs.next()) {
                System.out.println("索引: " + rs.getString("indexname"));
                System.out.println("定义: " + rs.getString("indexdef"));
            }
        }
    }

    @Test
    public void run() throws Exception {
        Class.forName("org.postgresql.Driver");

        List<Chunk> chunks = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(URL, "postgres", "123456");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT embedding_id, text, length(text) AS len FROM medical_documents ORDER BY embedding_id")) {
            while (rs.next()) {
                chunks.add(new Chunk(rs.getString("embedding_id"), rs.getString("text"), rs.getInt("len")));
            }
        }

        System.out.println("===== 语料库 chunk 总数：" + chunks.size() + " =====");
        for (Chunk ch : chunks) {
            System.out.printf("[%s] len=%d%n%s%n%n", ch.id.substring(0, Math.min(8, ch.id.length())), ch.len, ch.text);
        }

        List<EvalItem> items = loadEvalSet();
        System.out.println("===== 评估集事实覆盖检查（口径 = 召回率评估的 relevantIds）=====");
        for (EvalItem item : items) {
            for (String exp : item.expected) {
                boolean found = chunks.stream().anyMatch(ch -> ch.text != null && ch.text.contains(exp));
                System.out.printf("%s | %s | %s%n", found ? "命中" : "缺失", exp, item.query);
            }
        }
    }

    private List<EvalItem> loadEvalSet() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/rag-eval.json")) {
            return new ObjectMapper().readValue(in, new TypeReference<List<EvalItem>>() {});
        }
    }

    static class Chunk {
        final String id;
        final String text;
        final int len;
        Chunk(String id, String text, int len) { this.id = id; this.text = text; this.len = len; }
    }

    static class EvalItem {
        public String query;
        public List<String> expected;
    }
}
