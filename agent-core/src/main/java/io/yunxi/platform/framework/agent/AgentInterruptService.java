package io.yunxi.platform.framework.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 执行中断服务
 * <p>
 * 封装 AgentScope SDK 原生 interrupt() API，实现：
 * - Agent 执行中断
 * - 带消息的中断（用户介入）
 * - Agent 执行状态查询
 * - 分布式支持（基于 Redis）
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Service
public class AgentInterruptService {

    /** 中断消息 Redis Key 前缀 */
    private static final String INTERRUPT_KEY_PREFIX = "agent:interrupt:";
    /** 状态 Redis Key 前缀 */
    private static final String STATUS_KEY_PREFIX = "agent:status:";
    /** 状态缓存 TTL */
    private static final Duration STATUS_TTL = Duration.ofMinutes(30);

    /** Agent 领域服务 */
    private final AgentDomainService agentDomainService;
    /** Redis 模板 */
    private final StringRedisTemplate redisTemplate;

    /**
     * Agent 状态缓存（内存级缓存，用于快速查询）
     */
    private final Map<String, AgentStatus> statusCache = new ConcurrentHashMap<>();

    /**
     * 构造 Agent 中断服务
     *
     * @param agentDomainService Agent 领域服务
     * @param redisTemplate      Redis 模板
     */
    @Autowired
    public AgentInterruptService(AgentDomainService agentDomainService,
            StringRedisTemplate redisTemplate) {
        this.agentDomainService = agentDomainService;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 中断 Agent 执行
     *
     * @param agentName Agent 名称
     * @return 中断结果
     */
    public InterruptResult interrupt(String agentName) {
        return interrupt(agentName, null);
    }

    /**
     * 带消息的中断 Agent 执行（用户介入）
     *
     * @param agentName Agent 名称
     * @param message   用户消息（可选，用于提供修正或指导）
     * @return 中断结果
     */
    public InterruptResult interrupt(String agentName, String message) {
        log.info("中断 Agent: {}, message: {}", agentName, message);

        try {
            // 获取 Agent 实例
            ReActAgent agent = agentDomainService.getReActAgent(agentName);
            if (agent == null) {
                return InterruptResult.failure("Agent not found: " + agentName);
            }

            // 执行中断
            if (message != null && !message.isBlank()) {
                // 带消息的中断（用户介入）
                Msg userMessage = Msg.builder()
                        .textContent(message)
                        .name("user")
                        .role(io.agentscope.core.message.MsgRole.USER)
                        .build();
                agent.interrupt(userMessage);
                log.info("Agent [{}] 已中断（带消息）", agentName);
            } else {
                // 普通中断
                agent.interrupt();
                log.info("Agent [{}] 已中断", agentName);
            }

            // 更新状态
            updateAgentStatus(agentName, AgentState.INTERRUPTED, message);

            return InterruptResult.success("Agent interrupted successfully");

        } catch (Exception e) {
            log.error("中断 Agent 失败: {}", e.getMessage(), e);
            return InterruptResult.failure("Interrupt failed: " + e.getMessage());
        }
    }

    /**
     * 查询 Agent 执行状态
     *
     * @param agentName Agent 名称
     * @return Agent 状态
     */
    public AgentStatus getAgentStatus(String agentName) {
        // 先查内存缓存
        AgentStatus cachedStatus = statusCache.get(agentName);
        if (cachedStatus != null) {
            return cachedStatus;
        }

        // 查 Redis
        String statusKey = STATUS_KEY_PREFIX + agentName;
        String state = redisTemplate.opsForValue().get(statusKey);

        if (state != null) {
            AgentStatus status = new AgentStatus();
            status.setAgentName(agentName);
            status.setState(AgentState.valueOf(state));
            statusCache.put(agentName, status);
            return status;
        }

        // 查 Agent 实例状态
        try {
            ReActAgent agent = agentDomainService.getReActAgent(agentName);
            if (agent == null) {
                return AgentStatus.notFound(agentName);
            }

            // AgentScope 的 Agent 没有公开的 isRunning 方法
            // 我们假设 Agent 处于 IDLE 状态（如果存在且未被中断标记）
            AgentStatus status = new AgentStatus();
            status.setAgentName(agentName);
            status.setState(AgentState.IDLE);
            return status;

        } catch (Exception e) {
            return AgentStatus.notFound(agentName);
        }
    }

    /**
     * 恢复 Agent 执行（清除中断状态）
     *
     * @param agentName Agent 名称
     * @return 恢复结果
     */
    public InterruptResult resume(String agentName) {
        log.info("恢复 Agent: {}", agentName);

        // 清除中断标记
        clearAgentStatus(agentName);

        return InterruptResult.success("Agent resumed, status cleared");
    }

    /**
     * 更新 Agent 状态
     */
    private void updateAgentStatus(String agentName, AgentState state, String message) {
        AgentStatus status = new AgentStatus();
        status.setAgentName(agentName);
        status.setState(state);
        status.setMessage(message);
        status.setTimestamp(System.currentTimeMillis());

        // 更新内存缓存
        statusCache.put(agentName, status);

        // 更新 Redis
        String statusKey = STATUS_KEY_PREFIX + agentName;
        redisTemplate.opsForValue().set(statusKey, state.name(), STATUS_TTL);

        // 记录中断消息
        if (message != null) {
            String interruptKey = INTERRUPT_KEY_PREFIX + agentName;
            redisTemplate.opsForValue().set(interruptKey, message, STATUS_TTL);
        }

        log.debug("Agent 状态已更新: {} -> {}", agentName, state);
    }

    /**
     * 清除 Agent 状态
     */
    private void clearAgentStatus(String agentName) {
        statusCache.remove(agentName);

        String statusKey = STATUS_KEY_PREFIX + agentName;
        String interruptKey = INTERRUPT_KEY_PREFIX + agentName;
        redisTemplate.delete(statusKey);
        redisTemplate.delete(interruptKey);
    }

    /**
     * 中断结果
     */
    @Data
    public static class InterruptResult {
        private boolean success;
        private String message;

        private InterruptResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public static InterruptResult success(String message) {
            return new InterruptResult(true, message);
        }

        public static InterruptResult failure(String message) {
            return new InterruptResult(false, message);
        }
    }

    /**
     * Agent 状态
     */
    @Data
    public static class AgentStatus {
        private String agentName;
        private AgentState state;
        private String message;
        private Long timestamp;

        public static AgentStatus notFound(String agentName) {
            AgentStatus status = new AgentStatus();
            status.setAgentName(agentName);
            status.setState(AgentState.NOT_FOUND);
            return status;
        }
    }

    /**
     * Agent 状态枚举
     */
    public enum AgentState {
        IDLE, // 空闲
        RUNNING, // 执行中
        INTERRUPTED, // 已中断
        NOT_FOUND // 不存在
    }
}