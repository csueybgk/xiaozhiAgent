package com.hxm.java.ai.langchain4j;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.AugmentationResult;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Metadata;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 答案质量评估（第二层，LLM-as-Judge）。
 *
 * <p>第一层（{@link RagEvaluationTest}）只评「检索召回」，不评生成质量。
 * 这里复用生产检索增强器，让模型基于检索到的资料生成答案，再由裁判模型从两个维度打分：</p>
 * <ul>
 *   <li><b>正确性</b>：回答是否答对了用户问题、且与参考答案要点一致；</li>
 *   <li><b>忠实度</b>：回答是否忠实于检索到的资料，没有编造资料之外的内容（反幻觉）。</li>
 * </ul>
 */
@SpringBootTest(classes = XiaozhiApp.class)
public class RagAnswerQualityTest {

    @Autowired
    @Qualifier("retrievalAugmentorXiaozhi")
    private RetrievalAugmentor retrievalAugmentor;

    @Autowired
    @Qualifier("openAiChatModel")
    private ChatModel chatModel;   // DeepSeek 非流式：既当生成模型，也当裁判模型

    private String systemPrompt;

    @Test
    public void evaluateAnswerQuality() throws Exception {
        systemPrompt = loadSystemPrompt();
        List<EvalItem> items = loadEvalSet();

        int correctGood = 0, correctPartial = 0;
        int faithfulGood = 0, faithfulPartial = 0;

        System.out.println();
        System.out.println("==================== RAG 答案质量评估（LLM-as-Judge）====================");

        for (int i = 0; i < items.size(); i++) {
            EvalItem item = items.get(i);
            Generation gen = generate(item.query);
            Verdict correctness = judgeCorrectness(item, gen.answer);
            Verdict faithfulness = judgeFaithfulness(item, gen);

            if (correctness == Verdict.GOOD) correctGood++;
            else if (correctness == Verdict.PARTIAL) correctPartial++;
            if (faithfulness == Verdict.GOOD) faithfulGood++;
            else if (faithfulness == Verdict.PARTIAL) faithfulPartial++;

            System.out.println();
            System.out.printf("[%02d] 问题：%s%n", i + 1, item.query);
            System.out.printf("     参考要点：%s%n", String.join("；", item.expected));
            System.out.printf("     模型回答：%s%n", gen.answer);
            System.out.printf("     检索片段数：%d   正确性：%s   忠实度：%s%n",
                    gen.contents.size(), correctness.label(), faithfulness.label());
        }

        int n = items.size();
        System.out.println();
        System.out.println("=========================== 汇总 ===========================");
        System.out.printf("正确性（完全正确 %d / 部分正确 %d / 共 %d 条）  →  加权正确率 = %.3f%n",
                correctGood, correctPartial, n, (correctGood + 0.5 * correctPartial) / n);
        System.out.printf("忠实度（完全忠实 %d / 部分忠实 %d / 共 %d 条）  →  加权忠实度 = %.3f%n",
                faithfulGood, faithfulPartial, n, (faithfulGood + 0.5 * faithfulPartial) / n);
        System.out.println("===========================================================");
        System.out.println("说明：加权分 = (完全*1.0 + 部分*0.5) / 条目数，满分 1.0");
    }

    /** 复用生产检索增强器：查询改写 → 混合检索 → 内容注入，得到与线上一致的增强后消息 */
    private Generation generate(String question) {
        ChatMessage userMsg = UserMessage.from(question);
        AugmentationRequest request = new AugmentationRequest(userMsg, Metadata.from(userMsg, null, null));
        AugmentationResult result = retrievalAugmentor.augment(request);

        String answer = chatModel.chat(
                List.of(SystemMessage.from(systemPrompt), result.chatMessage())).aiMessage().text();
        return new Generation(answer, result.contents());
    }

    /** 正确性：回答 vs 参考答案要点 */
    private Verdict judgeCorrectness(EvalItem item, String answer) {
        String prompt = "你是医疗问答评估裁判。请判断「模型回答」是否正确回答了「用户问题」，"
                + "且与「参考答案要点」一致（意思对即可，不必逐字相同）。\n\n"
                + "用户问题：" + item.query + "\n\n"
                + "参考答案要点：" + String.join("；", item.expected) + "\n\n"
                + "模型回答：" + answer + "\n\n"
                + "请先只输出一行结论（三选一：正确 / 部分正确 / 错误），再换行用一句话说明理由。";
        return parseVerdict(chatModel.chat(prompt));
    }

    /** 忠实度：回答 vs 检索到的资料（判断是否编造资料之外的内容） */
    private Verdict judgeFaithfulness(EvalItem item, Generation gen) {
        String context = gen.contents.stream()
                .map(c -> c.textSegment().text())
                .collect(Collectors.joining("\n---\n"));

        String prompt = "你是医疗问答评估裁判。请判断「模型回答」是否忠实于「检索到的资料」："
                + "回答中的每一个事实都能在资料中找到依据，没有编造资料之外的内容。\n\n"
                + "检索到的资料：\n" + context + "\n\n"
                + "用户问题：" + item.query + "\n\n"
                + "模型回答：" + gen.answer() + "\n\n"
                + "请先只输出一行结论（三选一：忠实 / 部分忠实 / 不忠实），再换行用一句话说明理由。";
        return parseVerdict(chatModel.chat(prompt));
    }

    /** 从裁判输出第一行解析结论，映射到 GOOD / PARTIAL / BAD */
    private Verdict parseVerdict(String text) {
        if (text == null) return Verdict.BAD;
        String first = text.lines()
                .map(String::trim)
                .filter(l -> !l.isBlank())
                .findFirst()
                .orElse("");
        if (first.contains("部分")) return Verdict.PARTIAL;
        if (first.contains("错误") || first.contains("不忠实")) return Verdict.BAD;
        if (first.contains("正确") || first.contains("忠实")) return Verdict.GOOD;
        return Verdict.BAD;
    }

    private String loadSystemPrompt() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/zhaozhi-prompt-template.txt")) {
            if (in == null) throw new IllegalStateException("未找到 zhaozhi-prompt-template.txt");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("{{current_date}}", "2026-08-14");
        }
    }

    private List<EvalItem> loadEvalSet() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/rag-eval.json")) {
            if (in == null) throw new IllegalStateException("未找到 src/test/resources/rag-eval.json");
            return new ObjectMapper().readValue(in, new TypeReference<List<EvalItem>>() {});
        }
    }

    /** 一次生成的结果：答案 + 检索到的片段（供忠实度判定使用） */
    record Generation(String answer, List<Content> contents) {}

    static class EvalItem {
        public String query;
        public List<String> expected;
    }

    enum Verdict {
        GOOD("正确/忠实"), PARTIAL("部分正确/部分忠实"), BAD("错误/不忠实");

        private final String label;
        Verdict(String label) { this.label = label; }
        String label() { return label; }
    }
}
