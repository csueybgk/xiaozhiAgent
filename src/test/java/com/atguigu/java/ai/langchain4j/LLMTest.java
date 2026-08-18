package com.atguigu.java.ai.langchain4j;

import com.hxm.java.ai.langchain4j.XiaozhiApp;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;

@SpringBootTest(classes = XiaozhiApp.class)
public class LLMTest {

    /**
     * gpt-4o-mini语言模型接入测试
     */
    @Test
    public void testGPTDemo() {
        // 初始化模型
        OpenAiChatModel model = OpenAiChatModel.builder()
                // LangChain4j提供的代理服务器，该代理服务器会将演示密钥替换成真实密钥，
                // 再将请求转发给OpenAI API
                 .baseUrl("http://langchain4j.dev/demo/openai/v1")
                // 设置模型api地址（如果apiKey="demo"，则可省略baseUrl的配置）
                .apiKey("demo")           // 设置模型apiKey
                .modelName("gpt-4o-mini") // 设置模型名称
                .build();

        // 向模型提问
        String answer = model.chat("你好");
        // 输出结果
        System.out.println(answer);
    }


    /**
     * 整合SpringBoot
     */
    @Autowired
    private OpenAiChatModel openAiChatModel;

    @Test
    public void testSpringBoot() {
        // 向模型提问
        String answer = openAiChatModel.chat("你好");
        // 输出结果
        System.out.println(answer);
    }

    @Autowired
    private EmbeddingStore embeddingStore;
    @Autowired
    private EmbeddingModel embeddingModel;
    @Autowired
    @Qualifier("pgVectorDataSource")
    private DataSource pgVectorDataSource;
    @Test
    public void testUploadKnowledgeLibrary() {
        // 1. 清空旧语料：多次 ingestion 会在表中累积重复 chunk、以及不同分块配置留下的孤儿碎片，
        //    会挤占 top-K 召回名额，必须先清掉再重灌
        new JdbcTemplate(pgVectorDataSource).update("DELETE FROM medical_documents");

        Document document1 = FileSystemDocumentLoader.loadDocument(
                "C:/Users/gxds2/Desktop/agent/knowledge/医院信息.md");
        Document document2 = FileSystemDocumentLoader.loadDocument(
                "C:/Users/gxds2/Desktop/agent/knowledge/科室信息.md");
        Document document3 = FileSystemDocumentLoader.loadDocument(
                "C:/Users/gxds2/Desktop/agent/knowledge/神经内科.md");
        List<Document> documents = Arrays.asList(document1, document2, document3);

        // 2. 用适合中文的分块器：默认 300 字符递归切分会把标题、长句切成碎片，
        //    这里放宽到 500 字符 + 50 重叠，让每个 chunk 承载完整事实
        EmbeddingStoreIngestor
                .builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .documentSplitter(DocumentSplitters.recursive(500, 50))
                .build()
                .ingest(documents);
    }
}