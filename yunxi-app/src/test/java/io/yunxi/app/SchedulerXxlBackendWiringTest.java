package io.yunxi.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xxl.job.core.executor.XxlJobExecutor;
import io.agentscope.extensions.scheduler.AgentScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;

/** C11 验收：后端切换到 XXL-JOB 时完整应用上下文仍可加载，且 admin 未配置不拖垮启动。 */
@SpringBootTest(properties = {
        "yunxi.scheduler.enabled=true",
        "yunxi.scheduler.backend=xxl-job",
        "yunxi.scheduler.xxl-admin-addresses=",
        "yunxi.security.user-name=admin",
        "yunxi.security.user-password=test-secret"
})
@DirtiesContext
class SchedulerXxlBackendWiringTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void xxlJobBackendLoadsItsSchedulerAndExecutor() {
        assertEquals(1, context.getBeanNamesForType(AgentScheduler.class).length);
        AgentScheduler scheduler = context.getBean(AgentScheduler.class);
        assertTrue(scheduler instanceof YunxiXxlJobScheduler);
        assertEquals("yunxi-xxl-job", scheduler.getSchedulerType());
        assertEquals(1, context.getBeanNamesForType(XxlJobExecutor.class).length);
        assertEquals(1, context.getBeanNamesForType(ScheduledJobExecutor.class).length,
                "两种后端必须共用同一执行体");
    }
}
