package io.yunxi.platform.gateway.rule;

import io.yunxi.agent.rule.core.RuleContext;
import io.yunxi.agent.rule.core.RuleDefinition;
import io.yunxi.agent.rule.exception.RuleViolationException;
import io.yunxi.agent.rule.model.RulePriority;
import io.yunxi.agent.rule.model.RuleType;
import lombok.extern.slf4j.Slf4j;
import org.jeasy.rules.api.Facts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;

/**
 * 资源限制检查规则
 *
 * <p>
 * 规则逻辑：
 * </p>
 * <ul>
 * <li>检查 CPU 使用率是否超过阈值</li>
 * <li>检查内存使用率是否超过阈值</li>
 * <li>检查 Agent 实例数量是否超过限制</li>
 * <li>资源不足时拒绝任务执行，防止系统过载</li>
 * </ul>
 *
 * <p>
 * 迁移说明：从 agent-rule-engine 迁移至 agent-gateway，
 * 因为资源监控属于网关层职责。
 * </p>
 */
@Slf4j
@Component
public class ResourceLimitRule implements RuleDefinition {

    @Value("${rule.resource.cpu-threshold:80.0}")
    private double cpuThreshold;

    @Value("${rule.resource.memory-threshold:85.0}")
    private double memoryThreshold;

    @Value("${rule.resource.max-instances:0}")
    private int maxInstances; // 0 表示禁用实例数量检查

    @Value("${rule.resource.enabled:true}")
    private boolean enabled;

    private final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

    @Override
    public String getName() {
        return "resource-limit";
    }

    @Override
    public String getDescription() {
        return "检查系统资源使用情况，防止过载";
    }

    @Override
    public RuleType getType() {
        return RuleType.PRE;
    }

    @Override
    public int getPriority() {
        return RulePriority.HIGHEST.getValue();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.info("资源限制规则已{}", enabled ? "启用" : "禁用");
    }

    @Override
    public boolean evaluate(Facts facts) {
        if (!enabled) {
            log.debug("资源限制规则已禁用");
            return false;
        }

        RuleContext context = RuleContext.fromFacts(facts);

        // 检查 CPU 使用率
        double cpuUsage = getCpuUsage();
        if (cpuUsage > cpuThreshold) {
            log.warn("CPU 使用率过高: {}% > {}%", cpuUsage, cpuThreshold);
            context.setErrorMessage(String.format("CPU 使用率过高 (%.1f%% > %.1f%%)", cpuUsage, cpuThreshold));
            return true; // 触发规则
        }

        // 检查内存使用率
        double memoryUsage = getMemoryUsage();
        if (memoryUsage > memoryThreshold) {
            log.warn("内存使用率过高: {}% > {}%", memoryUsage, memoryThreshold);
            context.setErrorMessage(String.format("内存使用率过高 (%.1f%% > %.1f%%)", memoryUsage, memoryThreshold));
            return true; // 触发规则
        }

        // 检查实例数量（如果有）
        Integer activeInstances = getActiveInstances(context);
        if (activeInstances != null && activeInstances > maxInstances) {
            log.warn("活跃实例数过多: {} > {}", activeInstances, maxInstances);
            context.setErrorMessage(String.format("活跃实例数过多 (%d > %d)", activeInstances, maxInstances));
            return true; // 触发规则
        }

        log.debug("资源检查通过: CPU={:.1f}%, Memory={:.1f}%, Instances={}",
                cpuUsage, memoryUsage, activeInstances != null ? activeInstances : "N/A");

        return false; // 不触发规则
    }

    @Override
    public void execute(Facts facts) {
        RuleContext context = RuleContext.fromFacts(facts);
        String error = context.getErrorMessage();

        if (error == null) {
            error = "系统资源不足，拒绝执行任务";
        }

        log.error("资源限制检查失败: {}", error);

        // 抛出异常阻止任务执行
        throw new RuleViolationException(getName(), error);
    }

    /**
     * 获取 CPU 使用率
     *
     * @return CPU 使用率（百分比）
     */
    private double getCpuUsage() {
        try {
            // 使用系统负载作为 CPU 使用率的近似值
            double systemLoadAverage = osBean.getSystemLoadAverage();
            int availableProcessors = osBean.getAvailableProcessors();

            // 计算使用率：负载 / CPU 核心数 * 100
            if (systemLoadAverage > 0 && availableProcessors > 0) {
                return (systemLoadAverage / availableProcessors) * 100.0;
            }
        } catch (Exception e) {
            log.warn("获取 CPU 使用率失败", e);
        }

        // 如果无法获取，返回 0（不触发限制）
        return 0.0;
    }

    /**
     * 获取内存使用率
     *
     * @return 内存使用率（百分比）
     */
    private double getMemoryUsage() {
        try {
            long usedMemory = memoryBean.getHeapMemoryUsage().getUsed();
            long maxMemory = memoryBean.getHeapMemoryUsage().getMax();

            if (maxMemory > 0) {
                return (usedMemory * 100.0) / maxMemory;
            }
        } catch (Exception e) {
            log.warn("获取内存使用率失败", e);
        }

        // 如果无法获取，返回 0（不触发限制）
        return 0.0;
    }

    /**
     * 获取活跃实例数量
     *
     * @param context 规则上下文
     * @return 活跃实例数量（如果不可用则返回 null）
     */
    private Integer getActiveInstances(RuleContext context) {
        // 实例数量检查需要外部 Agent 管理器支持
        // 当前实现：从 RuleContext 属性中获取（如果有）
        Object instanceCount = context.getAttribute("activeInstanceCount");
        if (instanceCount instanceof Integer) {
            return (Integer) instanceCount;
        }

        // 如果没有提供实例计数，返回 null（表示不检查）
        // 生产环境建议通过依赖注入接入 AgentRegistry 获取真实计数
        return null;
    }
}
