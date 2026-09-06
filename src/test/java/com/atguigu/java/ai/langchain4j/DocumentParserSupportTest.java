package com.atguigu.java.ai.langchain4j;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多格式文档解析支持验证（纯解析器，不启动 Spring / 不连 DB / 不调 Embedding）。
 *
 * <p>背景：LangChain4j 单参 loadDocument(path) 的默认解析器由 classpath 上的 SPI 工厂决定，
 * 并不会按扩展名自动识别（Tika jar 在类路径时默认还会整体换成 Tika）。所以多格式加载必须
 * 像这里一样显式传入 parser —— 与灌库入口 {@link LLMTest#parserFor(java.nio.file.Path)} 同思路。</p>
 */
public class DocumentParserSupportTest {

    private static final String DIR = "C:/Users/gxds2/Desktop/agent/knowledge/";

    @Test
    public void mdParsedByTextParser() {
        assertParses(DIR + "神经内科.md", new TextDocumentParser(), "成立于1921年");
    }

    @Test
    public void pdfParsedByPdfBoxParser() {
        assertParses(DIR + "医院信息.pdf", new ApachePdfBoxDocumentParser(), "010-69151188");
    }

    @Test
    public void docxParsedByTikaParser() {
        assertParses(DIR + "科室信息.docx", new ApacheTikaDocumentParser(), "基本外科");
    }

    /**
     * 解析 → 非空 → 乱码占比达标（拦中文 PDF 的 CID 字体无 ToUnicode 乱码）→ 含期望片段。
     * <p>真乱码判据：PUA 私用区（0xE000-0xF8FF）+ Unicode 替换符（U+FFFD）。汉字、CJK 标点、
     * 全角/ASCII 都可读，不计为乱码。</p>
     */
    private void assertParses(String path, DocumentParser parser, String expectedFragment) {
        Document doc = FileSystemDocumentLoader.loadDocument(path, parser);
        String text = doc.text();
        assertTrue(text != null && !text.isBlank(), "解析结果不应为空: " + path);

        long nonSpace = text.chars().filter(c -> !Character.isWhitespace(c)).count();
        long garbage = text.chars()
                .filter(c -> !Character.isWhitespace(c))
                .filter(c -> (c >= 0xE000 && c <= 0xF8FF) || c == 0xFFFD)
                .count();
        double cleanRatio = nonSpace == 0 ? 1 : 1 - (double) garbage / nonSpace;
        assertTrue(cleanRatio > 0.95,
                "乱码占比过高(" + String.format("%.2f", 1 - cleanRatio) + ")，疑似 PDF CID 字体问题: " + path);
        assertTrue(text.contains(expectedFragment), "缺少期望片段[" + expectedFragment + "]: " + path);
    }
}
