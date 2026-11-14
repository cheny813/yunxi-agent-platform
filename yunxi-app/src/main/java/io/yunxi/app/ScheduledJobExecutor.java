package io.yunxi.app;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.yunxi.config.ConfigCenter;
import io.yunxi.definition.AgentSpec;
import io.yunxi.definition.TenantStatus;
import io.yunxi.runtime.AgentCommand;
import io.yunxi.runtime.ExecutionFacade;
import io.yunxi.runtime.Settle;
import io.yunxi.runtime.tenant.TenantContext;
import io.yunxi.runtime.tenant.TenantLifecycleService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 调度触发的唯一执行体：把调度引用翻译为平台统一执行请求。
 *
 * <p>每次触发都回读共享 ConfigCenter 中的最新 AgentSpec，并以最新调度段为执行参数来源
 * （prompt / 触发身份 / 租户），因此定义变更无需重建 Job 即可生效；JobDataMap 中的同类字段
 * 只作注册时诊断快照。执行经 {@link ExecutionFacade} 复用平台既有中间件 / 模型路由 /
 * 记忆 / 权限 / 审计语义，不新建裸 Agent。</p>
 */
@Component
public class ScheduledJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobExecutor.class);

    private final ExecutionFacade executionFacade;
    private final ConfigCenter configCenter;
    private final TenantLifecycleService tenantLifecycleService;

    public ScheduledJobExecutor(ExecutionFacade executionFacade, ConfigCenter configCenter,
            TenantLifecycleService tenantLifecycleService) {
        this.executionFacade = executionFacade;
        this.configCenter = configCenter;
        this.tenantLifecycleService = tenantLifecycleService;
    }

    public Settle run(ScheduledJobRequest request) {
        if (request == null || request.agentId() == null || request.agentId().isBlank()) {
            throw new IllegalArgumentException("scheduled job requires agentId");
        }
        // 读穿透：定义不在 JobStore 里，缺失时 ConfigCenter 明确抛错，绝不静默跳过。
        AgentSpec spec = configCenter.getAgentSpec(request.agentId());
        AgentSpec.ScheduleConfig schedule = spec.schedule();
        if (schedule == null) {
            throw new IllegalStateException(
                    "AgentSpec no longer declares a schedule for " + request.agentId());
        }

        String userId = firstNonBlank(schedule.triggerUserId(), "scheduler");
        String sessionId = "scheduled:" + request.agentId();
        String prompt = firstNonBlank(schedule.prompt(), "");
        String tenantId = schedule.tenantId();
        AgentCommand command = new AgentCommand(
                request.agentId(),
                null,
                userId,
                sessionId,
                List.of(Msg.builder().role(MsgRole.USER).textContent(prompt).build()),
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                "scheduled",
                null);

        // 准入闸门：SUSPENDED/ARCHIVED 租户的调度直接拒绝（null 租户 fail-open 为 ACTIVE）。
        TenantStatus status = tenantLifecycleService.admit(tenantId);
        log.info("调度触发 agentId={} userId={} tenantId={} sessionId={}",
                request.agentId(), userId, tenantId, sessionId);

        Settle settle = TenantContext.withTenant(executionFacade.settle(command), tenantId, status).block();
        if (settle == null) {
            throw new IllegalStateException("scheduled execution returned no result for "
                    + request.agentId());
        }
        return settle;
    }

    private static String firstNonBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
