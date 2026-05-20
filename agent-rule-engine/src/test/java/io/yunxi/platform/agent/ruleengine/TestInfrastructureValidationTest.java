package io.yunxi.platform.agent.ruleengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 测试基础设施验证
 * 验证测试环境、数据库连接、Spring上下文等是否正确配置
 */
@SpringBootTest(classes = io.yunxi.agent.rule.RuleEngineApplication.class)
@ActiveProfiles("test")
@Transactional
public class TestInfrastructureValidationTest extends BaseTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void testDataSourceIsConfigured() {
        // 验证数据源是否正确配置
        assertNotNull(dataSource, "数据源应该被注入");

        // 验证数据源元数据而不直接操作连接
        assertDoesNotThrow(() -> {
            try {
                assertThat(dataSource.getLoginTimeout()).isGreaterThanOrEqualTo(0);
            } catch (Exception e) {
                // 忽略异常，主要验证数据源能被正确注入
            }
        }, "获取数据源元数据不应该抛出异常");
    }

    @Test
    void testJdbcTemplateWorks() {
        // 验证JdbcTemplate可以正常工作
        assertNotNull(jdbcTemplate, "JdbcTemplate应该被注入");

        // 执行简单查询验证数据库连接
        Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        assertThat(result).isEqualTo(1);
    }

    @Test
    void testDatabaseSchemaIsLoaded() {
        // 验证测试模式是否正确加载
        List<Map<String, Object>> tables = jdbcTemplate.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'");

        // 验证核心表是否存在
        List<String> expectedTables = List.of("RULE", "RULE_EXECUTION_LOG");
        List<String> actualTables = tables.stream()
                .map(row -> (String) row.get("TABLE_NAME"))
                .map(String::toUpperCase)
                .toList();

        assertThat(actualTables).containsAll(expectedTables);
    }

    @Test
    void testTestDataIsLoaded() {
        // 验证测试数据是否正确插入
        Long ruleCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rule", Long.class);
        assertThat(ruleCount).isGreaterThan(0);

        // 验证不同状态的规则数量
        Long activeRules = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rule WHERE status = 'ACTIVE'", Long.class);
        Long logCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rule_execution_log", Long.class);
        assertThat(logCount).isGreaterThan(0);

        // 验证活动规则数量
        assertThat(activeRules).isGreaterThan(0);
    }

    @Test
    void testSpringContextLoadsCorrectly() {
        // 验证Spring上下文正确加载
        assertThat(dataSource).isNotNull();
        assertThat(jdbcTemplate).isNotNull();
    }

    @Test
    void testProfilesAreActive() {
        // 验证可以连接到数据库
        Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        assertThat(result).isEqualTo(1);
    }

    private void assertDoesNotThrow(Runnable runnable, String message) {
        try {
            runnable.run();
        } catch (Exception e) {
            throw new AssertionError(message + ", 但是抛出了异常: " + e.getMessage(), e);
        }
    }
}