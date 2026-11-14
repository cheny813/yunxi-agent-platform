package io.yunxi.app;

import io.agentscope.core.event.AgentEvent;
import io.yunxi.config.InMemoryConfigCenter;
import io.yunxi.definition.AgentSpec;
import io.yunxi.runtime.AgentCommand;
import io.yunxi.runtime.ExecutionFacade;
import io.yunxi.runtime.Settle;
import io.yunxi.runtime.tenant.TenantContext;
import io.yunxi.runtime.tenant.TenantLifecycleService;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 调度验收测试的公共构造物：不依赖真实模型、数据库或外部调度中心。 */
final class SchedulerTestSupport {

    private SchedulerTestSupport() {}

    static AgentSpec spec(String agentId, AgentSpec.ScheduleConfig schedule) {
        return new AgentSpec(
                new AgentSpec.Identity(agentId, agentId, "d"),
                null, null, null, null, null,
                new AgentSpec.SubagentsConfig(List.of()), null, schedule);
    }

    static AgentSpec.ScheduleConfig cron(String expression) {
        return new AgentSpec.ScheduleConfig(
                "CRON", expression, null, null, null, "UTC", "daily", "ops", "t1");
    }

    static InMemoryConfigCenter configCenterWith(AgentSpec... specs) {
        InMemoryConfigCenter center = new InMemoryConfigCenter();
        for (AgentSpec spec : specs) {
            center.registerAgentSpec(spec);
        }
        return center;
    }

    static final class StubExecutionFacade implements ExecutionFacade {
        final List<AgentCommand> commands = new CopyOnWriteArrayList<>();
        final List<String> tenants = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public Flux<AgentEvent> execute(AgentCommand command) {
            commands.add(command);
            return Flux.empty();
        }

        @Override
        public Mono<Settle> settle(AgentCommand command) {
            return Mono.defer(() -> {
                commands.add(command);
                tenants.add(TenantContext.current());
                latch.countDown();
                return Mono.just(Settle.ok(command, "ok"));
            });
        }
    }

    static ScheduledJobExecutor executor(
            InMemoryConfigCenter configCenter, StubExecutionFacade facade) {
        return new ScheduledJobExecutor(facade, configCenter, new TenantLifecycleService(configCenter));
    }
}
