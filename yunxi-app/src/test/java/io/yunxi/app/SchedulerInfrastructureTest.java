package io.yunxi.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 基础设施自包含验证：JDBC 表脚本完整、配置片段已纳入加载清单、源码无硬编码凭证。 */
class SchedulerInfrastructureTest {

    private static final Set<String> REQUIRED_TABLES = Set.of(
            "QRTZ_JOB_DETAILS", "QRTZ_TRIGGERS", "QRTZ_SIMPLE_TRIGGERS",
            "QRTZ_SIMPROP_TRIGGERS", "QRTZ_CRON_TRIGGERS", "QRTZ_BLOB_TRIGGERS",
            "QRTZ_CALENDARS", "QRTZ_PAUSED_TRIGGER_GRPS", "QRTZ_FIRED_TRIGGERS",
            "QRTZ_SCHEDULER_STATE", "QRTZ_LOCKS");

    private static Path repoFile(String relative) {
        return Path.of(System.getProperty("basedir", "."), "..", relative).normalize();
    }

    @Test
    void quartzSchemaContainsAllElevenJdbcJobStoreTables() throws Exception {
        Path sql = repoFile("deploy/infra/init/quartz-tables.sql");
        assertTrue(Files.exists(sql), "缺少 JDBC JobStore 建表脚本：" + sql.toAbsolutePath());
        String content = Files.readString(sql).toUpperCase();

        Matcher matcher = Pattern.compile("CREATE\\s+TABLE\\s+(\\w+)").matcher(content);
        Set<String> declared = matcher.results()
                .map(m -> m.group(1).toUpperCase())
                .collect(Collectors.toSet());
        for (String table : REQUIRED_TABLES) {
            assertTrue(declared.contains(table), "建表脚本缺少 " + table);
        }
    }

    @Test
    void schedulerConfigIsLoadedAndHasNoHardcodedCredentials() throws Exception {
        String config = Files.readString(
                repoFile("yunxi-config/src/main/resources/config/scheduler.yml"));
        assertTrue(config.contains("YUNXI_SCHEDULER_ENABLED:false"), "默认必须关闭");
        assertTrue(config.contains("${MYSQL_PASSWORD:}"), "口令须走环境变量占位");
        assertFalse(config.contains("password: root"), "源码不得硬编码凭证");

        String imports = Files.readString(
                repoFile("yunxi-config/src/main/resources/config/imports.yml"));
        assertTrue(imports.contains("classpath:config/scheduler.yml"),
                "配置片段必须纳入唯一加载清单");
    }
}
