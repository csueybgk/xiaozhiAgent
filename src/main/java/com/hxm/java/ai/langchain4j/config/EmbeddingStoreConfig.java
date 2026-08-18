package com.hxm.java.ai.langchain4j.config;

import com.zaxxer.hikari.HikariDataSource;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class EmbeddingStoreConfig {

    // 独立的 PG 数据源，避免和 MySQL 的 DataSource 冲突
    @Bean("pgVectorDataSource")
    public DataSource pgVectorDataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:postgresql://192.168.116.130:5432/rag_db");
        dataSource.setUsername("postgres");
        dataSource.setPassword("123456");
        dataSource.setMaximumPoolSize(5);
        return dataSource;
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(
            @Qualifier("pgVectorDataSource") DataSource dataSource) {
        // createTable 默认 true，首次 ingest 时会自动建表，schema 保证与 langchain4j 匹配
        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(dataSource)
                .table("medical_documents")
                .dimension(1024) // text-embedding-v3 固定 1024 维，硬编码避免启动时调 API 探测
                .build();
    }
}
