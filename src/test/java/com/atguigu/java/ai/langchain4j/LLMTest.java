package com.atguigu.java.ai.langchain4j;

import com.hxm.java.ai.langchain4j.XiaozhiApp;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
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
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

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

        // 2. 规范语料 = 3 个 .md：评估集（rag-eval.json）就是按这份语料标定的，
        //    混合检索 Recall@1 = 1.000 依赖它的分块布局。
        //    实测教训：若把某份知识换成同名 .pdf/.docx，多格式能正常解析入库，但 pdf 常是
        //    「重排版」文本而非 md 的忠实转换，500/50 切分点会漂移，导致个别查询 top-1 掉出
        //    （如乘车路线那条 Recall@1 1.000→0.929）。所以评估语料保持 md，多格式能力在
        //    parserFor 里提供，演示时把下面的路径换成 pdf/docx 即可（见 DocumentParserSupportTest）。
        List<Path> paths = List.of(
                Path.of("C:/Users/gxds2/Desktop/agent/knowledge/神经内科.md"),
                Path.of("C:/Users/gxds2/Desktop/agent/knowledge/医院信息.md"),
                Path.of("C:/Users/gxds2/Desktop/agent/knowledge/科室信息.md"));
        List<Document> documents = paths.stream()
                .map(path -> FileSystemDocumentLoader.loadDocument(path, parserFor(path)))
                .collect(Collectors.toList());

        // 3. 用适合中文的分块器：默认 300 字符递归切分会把标题、长句切成碎片，
        //    这里放宽到 500 字符 + 50 重叠，让每个 chunk 承载完整事实
        EmbeddingStoreIngestor
                .builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .documentSplitter(DocumentSplitters.recursive(500, 50))
                .build()
                .ingest(documents);
    }

    /** 按扩展名分派文档解析器；不认识的后缀直接抛异常拒绝，宁可失败也不静默解析成乱码 */
    private static DocumentParser parserFor(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".md") || name.endsWith(".txt")) {
            return new TextDocumentParser();
        }
        if (name.endsWith(".pdf")) {
            return new ApachePdfBoxDocumentParser();
        }
        if (name.endsWith(".doc") || name.endsWith(".docx")
                || name.endsWith(".xls") || name.endsWith(".xlsx")) {
            return new ApacheTikaDocumentParser();
        }
        throw new IllegalArgumentException("不支持的文件类型: " + path);
    }
}