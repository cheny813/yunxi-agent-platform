package io.yunxi.runtime.config;

import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.skill.JdbcAgentSkillRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * JDBC 技能仓库端到端验证（默认跳过，须 {@code -Dyunxi.e2e.jdbc=true} 显式开启）。
 *
 * <p>验证 yunxi 复用的框架原生 {@link JdbcAgentSkillRepository} 在真实 MySQL 上可完成建表、保存、读取闭环，
 * 即「复用调度 MySQL + 预建表」方案的运行时可行性。连接参数可通过系统属性覆盖：
 * {@code yunxi.e2e.jdbc.url / .user / .password}，默认指向本地 3306 的 yunxi_agent_platform 库。
 *
 * <p>表结构取自 {@code deploy/infra/init/skill-store.sql}（框架 JdbcAgentSkillRepository 构造器不自动建表）。
 */
class SkillRepositoryJdbcE2eTest {

    private static final boolean ENABLED = Boolean.getBoolean("yunxi.e2e.jdbc");
    private static HikariDataSource dataSource;
    private static JdbcAgentSkillRepository repository;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(ENABLED, "跳过：未设置 -Dyunxi.e2e.jdbc=true");

        String url = System.getProperty(
                "yunxi.e2e.jdbc.url",
                "jdbc:mysql://127.0.0.1:3306/yunxi_agent_platform?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true");
        String user = System.getProperty("yunxi.e2e.jdbc.user", "root");
        String password = System.getProperty("yunxi.e2e.jdbc.password", "root");

        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(user);
        dataSource.setPassword(password);
        dataSource.setPoolName("yunxi-skill-e2e");

        // 预建表（与 deploy/infra/init/skill-store.sql 一致；框架不自动建表）。
        String sqlPath = System.getProperty(
                "yunxi.e2e.skillSql", "../deploy/infra/init/skill-store.sql");
        String ddl = Files.readString(Path.of(sqlPath));
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            for (String stmt : ddl.split(";")) {
                String trimmed = stmt.trim();
                if (!trimmed.isEmpty()) {
                    st.execute(trimmed);
                }
            }
        }

        AbstractJdbcDialect dialect =
                AbstractJdbcDialect.from(dataSource).enableSkillTables(true).build();
        repository = new JdbcAgentSkillRepository(dataSource, dialect, true);
    }

    @Test
    void saveAndGetRoundTrip() {
        AgentSkill skill = new AgentSkill(
                "e2e_demo_skill",
                "端到端验证技能",
                "# demo\n示例技能内容",
                Map.of("note.txt", "hello-resource"),
                "jdbc");

        boolean saved = repository.save(List.of(skill), true);
        Assertions.assertTrue(saved, "save 应返回 true");

        AgentSkill loaded = repository.getSkill("e2e_demo_skill");
        Assertions.assertNotNull(loaded, "应能按名读回技能");
        Assertions.assertEquals("e2e_demo_skill", loaded.getName());
        Assertions.assertEquals("端到端验证技能", loaded.getDescription());
        Assertions.assertEquals("# demo\n示例技能内容", loaded.getSkillContent());
        Assertions.assertEquals("hello-resource", loaded.getResource("note.txt"));
        Assertions.assertTrue(repository.getAllSkillNames().contains("e2e_demo_skill"));
    }

    @AfterAll
    static void tearDown() {
        if (repository == null || dataSource == null) {
            return;
        }
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS agentscope_skill_resources");
            st.execute("DROP TABLE IF EXISTS agentscope_skills");
        } catch (Exception ignored) {
            // 清理失败不影响验证结论
        }
        dataSource.close();
    }
}
