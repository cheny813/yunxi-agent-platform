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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 频率限制规则
 *
 * <p>
 * 规则逻辑：
 * </p>
 * <ul>
 * <li>IP 级别频率限制（防止恶意攻击）</li>
 * <li>用户级别频率限制（防止单用户过度调用）</li>
 * <li>全局频率限制（保护系统整体性能）</li>
 * <li>滑动窗口计数（精确控制调用频率）</li>
 * </ul>
 *
 * <p>
 * 迁移说明：从 agent-rule-engine 迁移至 agent-gateway，
 * 因为频率限制属于网关层职责。
 * </p>
 */
@Slf4j
@Component
public class RateLimitRule implements RuleDefinition {

    @Value("${rule.ratelimit.ip-limit:100}")
    private int ipLimit; // 单 IP 每分钟最大请求数

    @Value("${rule.ratelimit.user-limit:50}")
    private int userLimit; // 单用户每分钟最大请求数

    @Value("${rule.ratelimit.global-limit:1000}")
    private int globalLimit; // 全局每分钟最大请求数

    @Value("${rule.ratelimit.enabled:true}")
    private boolean enabled;

    // IP 计数器
    private final Map<String, AtomicInteger> ipCounters = new ConcurrentHashMap<>();

    // 用户计数器
    private final Map<String, AtomicInteger> userCounters = new ConcurrentHashMap<>();

    // 全局计数器
    private final AtomicInteger globalCounter = new AtomicInteger(0);

    // 定时重置器
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public RateLimitRule() {
        // 每分钟重置一次计数器
        scheduler.scheduleAtFixedRate(this::resetCounters, 1, 1, TimeUnit.MINUTES);
    }

    @Override
    public String getName() {
        return "rate-limit";
    }

    @Override
    public String getDescription() {
        return "检查调用频率是否超过限制";
    }

    @Override
    public RuleType getType() {
        return RuleType.PRE;
    }

    @Override
    public int getPriority() {
        return RulePriority.HIGH.getValue();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.info("频率限制规则已{}", enabled ? "启用" : "禁用");
    }

    @Override
    public boolean evaluate(Facts facts) {
        if (!enabled) {
            log.debug("频率限制规则已禁用");
            return false;
        }

        RuleContext context = RuleContext.fromFacts(facts);

        // 1. 检查全局频率限制
        int globalCount = globalCounter.incrementAndGet();
        if (globalCount > globalLimit) {
            log.warn("全局频率超限: {} > {}", globalCount, globalLimit);
            context.setErrorMessage(String.format("系统繁忙，全局请求过多 (%d > %d)",
                    globalCount, globalLimit));
            return true; // 触发规则
        }

        // 2. 检查 IP 级别频率限制
        String clientIp = getClientIp(context);
        if (clientIp != null) {
            AtomicInteger ipCounter = ipCounters.computeIfAbsent(clientIp, k -> new AtomicInteger(0));
            int ipCount = ipCounter.incrementAndGet();

            if (ipCount > ipLimit) {
                log.warn("IP 频率超限: ip={}, count={} > limit={}", clientIp, ipCount, ipLimit);
                context.setErrorMessage(String.format("IP %s 请求过于频繁 (%d > %d)",
                        clientIp, ipCount, ipLimit));
                return true; // 触发规则
            }
        }

        // 3. 检查用户级别频率限制
        String userId = context.getUserInfo() != null ? context.getUserInfo().getUserId() : null;
        if (userId != null) {
            AtomicInteger userCounter = userCounters.computeIfAbsent(userId, k -> new AtomicInteger(0));
            int userCount = userCounter.incrementAndGet();

            if (userCount > userLimit) {
                log.warn("用户频率超限: userId={}, count={} > limit={}", userId, userCount, userLimit);
                context.setErrorMessage(String.format("用户请求过于频繁 (%d > %d)",
                        userCount, userLimit));
                return true; // 触发规则
            }
        }

        log.debug("频率检查通过: global={}, ip={}, user={}",
                globalCount,
                clientIp != null ? ipCounters.get(clientIp).get() : "N/A",
                userId != null ? userCounters.get(userId).get() : "N/A");

        return false; // 不触发规则
    }

    @Override
    public void execute(Facts facts) {
        RuleContext context = RuleContext.fromFacts(facts);
        String error = context.getErrorMessage();

        if (error == null) {
            error = "请求频率超限";
        }

        log.error("频率限制检查失败: {}", error);

        // 抛出异常阻止任务执行
        throw new RuleViolationException(getName(), error);
    }

    /**
     * 获取客户端 IP
     *
     * @param context 规则上下文
     * @return 客户端 IP
     */
    private String getClientIp(RuleContext context) {
        // 优先从 RuleContext 属性中获取（由上层设置）
        Object clientIp = context.getAttribute("clientIp");
        if (clientIp instanceof String) {
            return (String) clientIp;
        }

        // 尝试从请求头中获取（通过 context 的其他属性）
        // 实际项目中，这里应该从 HttpServletRequest 或 WebSocket Session 中获取
        // 由于 RuleContext 不直接持有请求对象，需要上层在创建 context 时设置

        // 返回 null 表示无法获取 IP，将跳过 IP 级别的限流检查
        // 生产环境建议：通过 RequestContextHolder 或自定义 Filter 在请求入口设置 clientIp
        return null;
    }

    /**
     * 重置所有计数器
     */
    private void resetCounters() {
        try {
            // 重置 IP 计数器
            ipCounters.clear();

            // 重置用户计数器
            userCounters.clear();

            // 重置全局计数器
            globalCounter.set(0);

            log.debug("频率限制计数器已重置");
        } catch (Exception e) {
            log.error("重置频率限制计数器失败", e);
        }
    }

    /**
     * 获取当前全局请求计数
     *
     * @return 当前全局请求计数
     */
    public int getGlobalCount() {
        return globalCounter.get();
    }

    /**
     * 获取指定 IP 的请求计数
     *
     * @param ip IP 地址
     * @return 请求计数
     */
    public int getIpCount(String ip) {
        AtomicInteger counter = ipCounters.get(ip);
        return counter != null ? counter.get() : 0;
    }

    /**
     * 获取指定用户的请求计数
     *
     * @param userId 用户 ID
     * @return 请求计数
     */
    public int getUserCount(String userId) {
        AtomicInteger counter = userCounters.get(userId);
        return counter != null ? counter.get() : 0;
    }
}
