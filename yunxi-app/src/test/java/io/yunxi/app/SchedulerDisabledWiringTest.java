package io.yunxi.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.extensions.scheduler.AgentScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * 默认关闭验证：启动完整应用上下文，确认调度装配类完全不参与，存量部署零回归。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "yunxi.security.user-name=admin",
        "yunxi.security.user-password=test-secret"
})
class SchedulerDisabledWiringTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void schedulerDisabledByDefaultDoesNotCreateAnySchedulerBean() {
        assertEquals(0, context.getBeanNamesForType(AgentScheduler.class).length,
                "默认关闭时不得创建任何框架调度器 Bean");
        assertEquals(0, context.getBeanNamesForType(SchedulerConfiguration.class).length,
                "条件装配类必须整体不生效");
        assertEquals(0, context.getBeanNamesForType(AgentSchedulerBootstrapper.class).length,
                "默认关闭时不得注册启动期布建器");
    }

    @Test
    void disabledContextHasNoQuartzSchedulerBean() {
        assertEquals(0, context.getBeanNamesForType(org.quartz.Scheduler.class).length,
                "关闭时不得要求 Quartz / MySQL / QRTZ_* 表存在");
    }
}
