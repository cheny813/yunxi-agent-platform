package io.yunxi.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.extensions.scheduler.AgentScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;

/** 开启态完整上下文验证：真实加载平台调度器 Bean，且默认后端语义保真。 */
@SpringBootTest(properties = {
        "yunxi.scheduler.enabled=true",
        "yunxi.security.user-name=admin",
        "yunxi.security.user-password=test-secret"
})
@DirtiesContext
class SchedulerBackendWiringTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void enabledContextLoadsHarnessSchedulerBean() {
        assertEquals(1, context.getBeanNamesForType(AgentScheduler.class).length);
        AgentScheduler scheduler = context.getBean(AgentScheduler.class);
        assertTrue(scheduler instanceof YunxiHarnessAgentScheduler);
        assertEquals("yunxi-harness-quartz", scheduler.getSchedulerType());
    }

    @Test
    void enabledContextLoadsBootstrapperAndExecutor() {
        assertEquals(1, context.getBeanNamesForType(AgentSchedulerBootstrapper.class).length);
        assertEquals(1, context.getBeanNamesForType(ScheduledJobExecutor.class).length);
        assertEquals(1, context.getBeanNamesForType(YunxiSpringBeanJobFactory.class).length);
    }
}
