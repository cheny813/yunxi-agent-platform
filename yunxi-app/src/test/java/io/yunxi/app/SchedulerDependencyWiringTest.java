package io.yunxi.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.extensions.scheduler.AgentScheduler;
import io.agentscope.extensions.scheduler.config.AgentConfig;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import io.agentscope.extensions.scheduler.quartz.QuartzAgentScheduler;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 依赖接线验证：框架调度 API 在编译期可见、可真实构造，且版本由 BOM 统一管理。
 */
class SchedulerDependencyWiringTest {

    @Test
    void frameworkSchedulerApiIsUsable() throws Exception {
        assertNotNull(AgentScheduler.class.getDeclaredMethod(
                "schedule", AgentConfig.class, ScheduleConfig.class));
        assertNotNull(QuartzAgentScheduler.class.getDeclaredMethod("builder"));
    }

    @Test
    void resolvedSchedulerArtifactCanBuildScheduleConfig() {
        ScheduleConfig config = ScheduleConfig.builder().cron("0 0 8 * * ?").build();
        assertEquals("CRON", config.getScheduleMode().name());
        assertEquals("0 0 8 * * ?", config.getCronExpression());
    }

    @Test
    void schedulerDependenciesDoNotPinTheirOwnVersion() throws Exception {
        Path pom = Path.of(System.getProperty("basedir", "."), "pom.xml");
        assertTrue(Files.exists(pom), "测试须在模块目录下运行，实际：" + pom.toAbsolutePath());
        String content = Files.readString(pom);

        for (String artifact : new String[] {
                "agentscope-extensions-scheduler-quartz",
                "agentscope-extensions-scheduler-common"}) {
            int at = content.indexOf("<artifactId>" + artifact + "</artifactId>");
            assertTrue(at >= 0, "缺少依赖声明：" + artifact);
            int start = content.lastIndexOf("<dependency>", at);
            int end = content.indexOf("</dependency>", at);
            String block = content.substring(start, end);
            assertFalse(block.contains("<version>"), artifact + " 版本必须由 BOM 锁定");
        }
    }
}
