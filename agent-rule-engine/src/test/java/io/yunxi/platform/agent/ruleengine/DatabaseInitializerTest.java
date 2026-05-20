package io.yunxi.platform.agent.ruleengine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据库初始化调试测试类
 * 用于诊断为什么data.sql文件没有自动执行
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:application-test.yml")
@Transactional
public class DatabaseInitializerTest {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializerTest.class);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    public void setUp() {
        log.info("数据源: {}", dataSource);
    }

    @Test
    public void testSchemaIsCreated() {
        // 验证表结构是否存在
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'", 
                Integer.class
        );
        
        log.info("数据库表数量: {}", tableCount);
        assertThat(tableCount).isGreaterThan(0);

        // 检查具体表是否存在
        Boolean ruleTableExists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = 'RULE'", 
                Boolean.class
        );
        
        log.info("RULE表是否存在: {}", ruleTableExists);
        assertThat(ruleTableExists).isTrue();

        Boolean ruleExecutionLogTableExists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = 'RULE_EXECUTION_LOG'", 
                Boolean.class
        );
        
        log.info("RULE_EXECUTION_LOG表是否存在: {}", ruleExecutionLogTableExists);
        assertThat(ruleExecutionLogTableExists).isTrue();
    }

    @Test
    public void testDataSqlCanBeExecuted() throws Exception {
        // 尝试手动执行data.sql文件
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/data.sql"));
            log.info("手动执行data.sql完成");
        }

        // 验证数据是否插入成功
        Long ruleCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rule", Long.class);
        log.info("手动插入后RULE表数据量: {}", ruleCount);
        assertThat(ruleCount).isEqualTo(3L);

        Long logCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rule_execution_log", Long.class);
        log.info("手动插入后RULE_EXECUTION_LOG表数据量: {}", logCount);
        assertThat(logCount).isEqualTo(3L);
    }

    @Test
    public void testShowTables() {
        // 显示所有表的信息
        jdbcTemplate.query("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'",
                (rs, rowNum) -> {
                    String tableName = rs.getString("TABLE_NAME");
                    log.info("存在的表: {}", tableName);
                    return tableName;
                }
        );
    }

    @Test
    public void testShowDataInTables() {
        // 检查RULE表数据
        Long ruleCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rule", Long.class);
        log.info("RULE表当前数据量: {}", ruleCount);

        if (ruleCount > 0) {
            jdbcTemplate.query("SELECT * FROM rule",
                    (rs, rowNum) -> {
                        log.info("RULE表记录: id={}, name={}", 
                                rs.getLong("id"), rs.getString("name"));
                        return null;
                    }
            );
        }

        // 检查RULE_EXECUTION_LOG表数据
        Long logCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rule_execution_log", Long.class);
        log.info("RULE_EXECUTION_LOG表当前数据量: {}", logCount);
    }
}