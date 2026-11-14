package io.yunxi.app;

import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import io.yunxi.definition.AgentSpec;
import io.yunxi.runtime.AgentDefinitionLoader;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

/**
 * 启动期把定义平面中含调度段的 AgentSpec 翻译成标量请求并注册到调度后端。
 *
 * <p>定义来源与 Agent 布建器相同（配置中心经 {@link AgentDefinitionLoader} 暴露），
 * JobStore 只持标量引用，不另存一份定义。执行内容变更由 Job 读穿透生效；触发时机变更由显式
 * 调用 {@link #register()} 按配置指纹幂等重排。</p>
 */
public class AgentSchedulerBootstrapper {

    private static final Logger log = LoggerFactory.getLogger(AgentSchedulerBootstrapper.class);
    private static final long BASE_STAGGER_MILLIS = 1000L;
    private static final long STAGGER_STEP_MILLIS = 500L;
    private static final long MAX_STAGGER_MILLIS = 10000L;

    private final AgentDefinitionLoader agentDefinitionLoader;
    private final YunxiAgentScheduler scheduler;

    public AgentSchedulerBootstrapper(
            AgentDefinitionLoader agentDefinitionLoader, YunxiAgentScheduler scheduler) {
        this.agentDefinitionLoader = agentDefinitionLoader;
        this.scheduler = scheduler;
    }

    /** 启动期注册；也可由部署流程显式调用以按最新定义幂等重排触发时机。 */
    @EventListener(ContextRefreshedEvent.class)
    public void register() {
        int periodicIndex = 0;
        for (AgentSpec spec : agentDefinitionLoader.loadAllSpecs()) {
            if (spec == null || spec.schedule() == null) {
                continue;
            }
            String agentId = spec.identity() == null ? null : spec.identity().agentId();
            if (isBlank(agentId)) {
                log.error("跳过调度定义：identity.agentId 缺失");
                continue;
            }
            AgentSpec.ScheduleConfig definition = spec.schedule();
            boolean periodic = isPeriodic(definition.mode());
            Long stagger = periodic ? staggerFor(periodicIndex) : null;
            try {
                ScheduleConfig config = toFrameworkConfig(definition, stagger);
                ScheduledJobRequest request = new ScheduledJobRequest(
                        agentId,
                        definition.tenantId(),
                        definition.prompt(),
                        definition.triggerUserId(),
                        "scheduled:" + agentId);
                scheduler.schedule(request, config);
                if (periodic) {
                    periodicIndex++;
                }
                log.info("已注册调度任务 agentId={} triggerUserId={} tenantId={} mode={}",
                        agentId,
                        defaultIfBlank(definition.triggerUserId(), "unknown"),
                        defaultIfBlank(definition.tenantId(), "default"),
                        definition.mode());
            } catch (Exception e) {
                log.error("跳过调度定义 agentId={} mode={}（{}）",
                        agentId, definition.mode(), e.getClass().getSimpleName(), e);
            }
        }
    }

    /** 只对周期任务错峰，并封顶，避免定义数量多时把首跑推迟到分钟级。 */
    static long staggerFor(int periodicIndex) {
        return Math.min(
                BASE_STAGGER_MILLIS + (long) periodicIndex * STAGGER_STEP_MILLIS,
                MAX_STAGGER_MILLIS);
    }

    /** 把定义平面纯数据翻译为框架调度配置；包可见以便验收断言直接检查构造结果。 */
    static ScheduleConfig toFrameworkConfig(
            AgentSpec.ScheduleConfig schedule, Long defaultInitialDelay) {
        ScheduleConfig.Builder builder = ScheduleConfig.builder();
        switch (schedule.mode()) {
            case "CRON" -> builder.cron(requireNonBlank(schedule.cron(), "schedule.cron"));
            case "FIXED_RATE" -> builder.fixedRate(
                    requireInterval(schedule.rateMillis(), "schedule.rateMillis"));
            case "FIXED_DELAY" -> builder.fixedDelay(
                    requireInterval(schedule.delayMillis(), "schedule.delayMillis"));
            case "NONE" -> { }
            default -> throw new IllegalArgumentException(
                    "unsupported schedule.mode=" + schedule.mode());
        }
        Long initialDelay = schedule.initialDelayMillis() != null
                ? requireNonNegative(schedule.initialDelayMillis(), "schedule.initialDelayMillis")
                : defaultInitialDelay;
        if (initialDelay != null) {
            builder.initialDelay(initialDelay);
        }
        if (!isBlank(schedule.zoneId())) {
            builder.zoneId(ZoneId.of(schedule.zoneId()).getId());
        }
        return builder.build();
    }

    private static boolean isPeriodic(String mode) {
        return "FIXED_RATE".equals(mode) || "FIXED_DELAY".equals(mode);
    }

    private static String requireNonBlank(String value, String field) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static Long requireInterval(Long value, String field) {
        if (value == null || value < AgentSpec.ScheduleConfig.MIN_INTERVAL_MILLIS) {
            throw new IllegalArgumentException(
                    field + " must be at least " + AgentSpec.ScheduleConfig.MIN_INTERVAL_MILLIS);
        }
        return value;
    }

    private static Long requireNonNegative(Long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }
}
