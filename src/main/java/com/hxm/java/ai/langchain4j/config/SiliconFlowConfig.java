package com.hxm.java.ai.langchain4j.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class SiliconFlowConfig {

    // 硅基流动 API Key（从环境变量 EMBEDDING_API_KEY 读取，避免硬编码进代码）
    @Value("${EMBEDDING_API_KEY}")
    private String apiKey;

    /**
     * 通过硅基流动的 OpenAI 兼容接口调用 BAAI/bge-large-zh-v1.5（1024 维），
     * 替换阿里云百炼 text-embedding-v3，避免依赖百炼 API Key。
     * 用 @Primary 覆盖 langchain4j 自动装配的百炼 embedding model。
     */
    @Bean
    @Primary
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .baseUrl("https://api.siliconflow.cn/v1")
                .apiKey(apiKey)
                .modelName("BAAI/bge-large-zh-v1.5")
                .build();
    }

    /**
     * ReRank 打分模型：粗召回后精排使用，复用同一把硅基流动 API Key。
     */
    @Bean
    public ScoringModel scoringModel() {
        return new SiliconFlowScoringModel(apiKey);
    }
}
