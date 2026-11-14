package io.yunxi.app;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.extensions.scheduler.ScheduleAgentTask;
import java.util.function.Consumer;
import reactor.core.publisher.Mono;

/** 从 JobStore 回读出的任务视图；执行与取消都委托给共享组件，不持有进程内副本。 */
public class ScheduledTaskView implements ScheduleAgentTask<Msg> {

    private final String name;
    private final ScheduledJobRequest request;
    private final ScheduledJobExecutor executor;
    private final Consumer<String> canceller;

    public ScheduledTaskView(
            String name,
            ScheduledJobRequest request,
            ScheduledJobExecutor executor,
            Consumer<String> canceller) {
        this.name = name;
        this.request = request;
        this.executor = executor;
        this.canceller = canceller;
    }

    public ScheduledJobRequest request() {
        return request;
    }

    @Override
    public String getId() {
        return "yunxi:" + name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Mono<Msg> run(Msg... msgs) {
        return Mono.fromCallable(() -> {
            executor.run(request);
            return Msg.builder().role(MsgRole.ASSISTANT).textContent("scheduled").build();
        });
    }

    @Override
    public void cancel() {
        canceller.accept(name);
    }
}
