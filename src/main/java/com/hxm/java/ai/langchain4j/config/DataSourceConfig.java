package com.hxm.java.ai.langchain4j.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

@Configuration
public class DataSourceConfig {

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    @Value("${spring.datasource.driver-class-name}")
    private String driverClassName;

    /**
     * 主数据源（MySQL），供 MyBatis-Plus 使用。
     * <p>显式声明 @Primary，避免与 pgVectorDataSource（PostgreSQL）冲突：
     * 若不声明，Spring Boot 自动配置会因已存在 DataSource bean 而跳过 MySQL 数据源创建，
     * 导致 MyBatis 误用 PostgreSQL 连接查询业务表。</p>
     * <p>注意：不要用 DataSourceBuilder + @ConfigurationProperties 的方式，
     * 因为 spring.datasource.url 无法正确映射到 HikariDataSource 的 jdbcUrl 属性。</p>
     */
    @Bean
    @Primary
    public DataSource dataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setDriverClassName(driverClassName);
        return dataSource;
    }
}
