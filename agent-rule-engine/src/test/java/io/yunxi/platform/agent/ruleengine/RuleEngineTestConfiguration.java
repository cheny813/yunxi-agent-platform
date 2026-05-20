package io.yunxi.platform.agent.ruleengine;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;

/**
 * 测试专用配置类，提供测试环境下的Bean定义
 */
@TestConfiguration
public class RuleEngineTestConfiguration {

    /**
     * 嵌入式内存数据库，用于测试环境的数据库操作
     */
    @Bean
    @Primary
    public DataSource dataSource() {
        EmbeddedDatabaseBuilder builder = new EmbeddedDatabaseBuilder();
        EmbeddedDatabase db = builder
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:test-schema.sql")
                .addScript("classpath:test-data.sql")
                .build();
        return db;
    }

    /**
     * 自定义测试属性配置
     */
    @Bean
    public TestProperties testProperties() {
        return new TestProperties();
    }

    /**
     * 测试属性配置类
     */
    public static class TestProperties {
        private String testEnvironment = "unit-test";
        private int maxTestIterations = 100;

        public String getTestEnvironment() {
            return testEnvironment;
        }

        public int getMaxTestIterations() {
            return maxTestIterations;
        }
    }
}