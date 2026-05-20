package io.yunxi.platform.business.rule;

import io.yunxi.agent.rule.core.RuleContext;
import io.yunxi.agent.rule.core.RuleDefinition;
import io.yunxi.agent.rule.model.RulePriority;
import io.yunxi.agent.rule.model.RuleType;
import lombok.extern.slf4j.Slf4j;
import org.jeasy.rules.api.Facts;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计日志规则
 *
 * <p>
 * 规则逻辑：
 * </p>
 * <ul>
 * <li>记录所有 Agent 操作日志</li>
 * <li>记录工具调用详情</li>
 * <li>记录任务执行结果</li>
 * </ul>
 *
 * 因为审计日志属于业务层职责。
 * </p>
 */
@Slf4j
@Component
public class AuditLogRule implements RuleDefinition {

    private final RuleExecutionLogRepository logRepository;

    public AuditLogRule(RuleExecutionLogRepository logRepository) {
        this.logRepository = logRepository;
    }

    @Override
    public String getName() {
        return "audit-log";
    }

    @Override
    public String getDescription() {
        return "记录审计日志";
    }

    @Override
    public RuleType getType() {
        return RuleType.POST;
    }

    @Override
    public int getPriority() {
        return RulePriority.LOWEST.getValue();
    }

    @Override
    public boolean evaluate(Facts facts) {
        // 审计日志规则始终触发
        return true;
    }

    @Override
    public void execute(Facts facts) {
        RuleContext context = RuleContext.fromFacts(facts);

        // 构建审计日志
        RuleExecutionLog auditLog = RuleExecutionLog.builder()
                .ruleName(getName())
                .agentId(context.getAgentId())
                .taskId(context.getTaskInfo() != null ? context.getTaskInfo().getTaskId() : null)
                .userId(context.getUserInfo() != null ? context.getUserInfo().getUserId() : null)
                .username(context.getUserInfo() != null ? context.getUserInfo().getUsername() : null)
                .triggered(true)
                .passed(context.getErrorMessage() == null)
                .errorMessage(context.getErrorMessage())
                .createdTime(LocalDateTime.now())
                .build();

        // 异步写入审计日志
        saveAsync(auditLog);

        log.info("审计日志已记录: agent={}, task={}, user={}",
                context.getAgentId(),
                context.getTaskInfo() != null ? context.getTaskInfo().getTaskId() : "N/A",
                context.getUserInfo() != null ? context.getUserInfo().getUsername() : "N/A");
    }

    /**
     * 异步保存审计日志
     *
     * @param auditLog 审计日志
     */
    @Async
    protected void saveAsync(RuleExecutionLog auditLog) {
        try {
            logRepository.save(auditLog);
        } catch (Exception e) {
            log.error("保存审计日志失败", e);
        }
    }
}
