package com.hxm.java.ai.langchain4j.config;

import com.hxm.java.ai.langchain4j.memory.SummaryChatMemory;
import com.hxm.java.ai.langchain4j.rag.GuardedContentInjector;
import com.hxm.java.ai.langchain4j.store.MongoChatMemoryStore;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.ExpandingQueryTransformer;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class XiaozhiAgentConfig {

    @Autowired
    private MongoChatMemoryStore mongoChatMemoryStore;

    @Autowired
    private EmbeddingStore embeddingStore;

    @Autowired
    private EmbeddingModel embeddingModel;

    // DeepSeek 非流式模型，用于 Multi-Query 查询改写
    @Autowired
    @Qualifier("openAiChatModel")
    private ChatModel chatModel;

    @Bean
    ChatMemoryProvider chatMemoryProviderXiaozhi() {
        return memoryId -> new SummaryChatMemory(
                memoryId,
                mongoChatMemoryStore,
                chatModel,   // DeepSeek 非流式模型，用于生成对话摘要
                20,          // 窗口上限：消息数超过则触发压缩
                10           // 压缩后保留最近 10 条完整消息
        );
    }

    /**
     * 向量语义检索器（供混合检索器内部调用）
     */
    @Bean("vectorRetriever")
    ContentRetriever vectorRetriever() {
        return EmbeddingStoreContentRetriever
                .builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .maxResults(20)    // 粗召回：放宽到 20 条，交给 ReRank 精排
                .minScore(0.0)     // 关闭相似度下限，召回阶段宁多勿漏
                .build();
    }

    /**
     * Multi-Query 查询改写：把用户口语化问题扩展为多个不同角度的检索查询，
     * 覆盖同义词与医学术语，提升召回覆盖率。
     *
     * <p>「是否检索」不再交给 LLM 判断（LLM 分类有随机性，会把「擅长看什么病」「服务热线是多少」
     * 等医疗/行政问题误判成【无需检索】，导致凭记忆瞎编）。改为确定性的关键词判断寒暄/闲聊，
     * LLM 只负责改写，保证评估结果稳定、可复现。</p>
     */
    @Bean("queryTransformerXiaozhi")
    QueryTransformer queryTransformerXiaozhi() {
        // 自定义中文改写模板：只改写、不判断是否检索；第一条必须原样保留用户问题，避免精确事实查询被稀释漏检
        PromptTemplate promptTemplate = PromptTemplate.from(
                "将用户的问题改写为 {{n}} 个检索查询，用于在医疗知识库中检索相关内容。\n"
                        + "改写要求：\n"
                        + "1. 第一个查询 = 用户问题原文（一字不改）；\n"
                        + "2. 其余查询从不同角度改写，使用同义词、医学术语或不同表述方式；\n"
                        + "3. 保持原意不变；\n"
                        + "4. 每个查询单独一行，不要编号、不要连字符、不要任何额外格式。\n"
                        + "\n"
                        + "用户问题：{{query}}");
        ExpandingQueryTransformer expander = ExpandingQueryTransformer.builder()
                .chatModel(chatModel)
                .promptTemplate(promptTemplate)
                .n(3) // 每次扩展为 3 个查询
                .build();

        // 确定性分流：只有明显的寒暄/闲聊才跳过检索，其余（含医院地址、电话、时间、路线、级别、科室）一律检索
        return query -> isNonMedical(query.text())
                ? List.of(Query.from("【无需检索】"))
                : expander.transform(query);
    }

    /** 明显与医院无关的寒暄/身份/闲聊才返回 true；短句 + 命中关键词，避免误伤医疗问句 */
    private boolean isNonMedical(String text) {
        if (text == null) {
            return true;
        }
        String t = text.replaceAll("[\\s\\p{P}。！？、，]", "");
        if (t.isEmpty()) {
            return true;
        }
        List<String> nonMedical = List.of(
                "你好", "您好", "嗨", "哈喽", "在吗", "早上好", "晚上好",
                "你是谁", "你叫什么", "你是什么", "介绍一下你",
                "谢谢", "感谢", "再见", "拜拜", "天气", "讲个笑话", "聊天");
        return t.length() <= 10 && nonMedical.stream().anyMatch(t::contains);
    }

    /**
     * 检索增强器：Multi-Query 查询改写 → 混合检索（向量 + 关键词 RRF 融合）→ 结果聚合。
     */
    @Bean("retrievalAugmentorXiaozhi")
    RetrievalAugmentor retrievalAugmentorXiaozhi(
            @Qualifier("queryTransformerXiaozhi") QueryTransformer queryTransformer,
            @Qualifier("hybridContentRetriever") ContentRetriever hybridContentRetriever) {
        return DefaultRetrievalAugmentor.builder()
                .queryTransformer(queryTransformer)
                .contentRetriever(hybridContentRetriever)
                .contentInjector(new GuardedContentInjector())   // 检索为空 → 明确说不知道，避免裸答
                .build();
    }
}
