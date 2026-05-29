package io.yunxi.platform.framework.agent;

import io.agentscope.core.agent.Agent;
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

    /** Redis Key 前缀：中断消息 */
    private static final String INTERRUPT_KEY_PREFIX = "agent:interrupt:";

    /** Redis Key 前缀：Agent 状态 */
    private static final String STATUS_KEY_PREFIX = "agent:status:";

    /** 状态缓存 TTL */
    private static final Duration STATUS_TTL = Duration.ofMinutes(30);

    /** Agent 领域服务 — 查找 Agent 实例 */
    private final AgentDomainService agentDomainService;

    /** Redis 模板（可选，用于分布式状态同步） */
    private final StringRedisTemplate redisTemplate;

    /** Agent 状态缓存（内存级，用于快速查询，避免 Redis 请求） */
    private final Map<String, AgentStatus> statusCache = new ConcurrentHashMap<>();

    public AgentInterruptService(AgentDomainService agentDomainService,
            StringRedisTemplate redisTemplate) {
        this.agentDomainService = agentDomainService;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 中断 Agent 执行（无消息）
     *
     * @param agentName Agent 名称
     * @return 中断结果
     */
    public InterruptResult interrupt(String agentName) {
        return interrupt(agentName, null);
    }

    /**
     * 带消息的中断 Agent 执行（用户介入）
     * <p>
     * 中断当前正在执行的 Agent，可附带用户修正信息。
     * AgentScope ReActAgent 收到中断消息后会将用户消息注入当前对话上下文，
     * 让 Agent 在下一次推理时考虑用户输入。
     * </p>
     *
     * @param agentName Agent 名称
     * @param message   用户消息（可选，用于提供修正或指导）
     * @return 中断结果
     */
    public InterruptResult interrupt(String agentName, String message) {
        log.info("中断 Agent: {}, message: {}", agentName, message);

        try {
            Agent agent = agentDomainService.findAgent(agentName);
            if (agent == null) {
                return InterruptResult.failure("Agent not found: " + agentName);
            }

            if (message != null && !message.isBlank()) {
                Msg userMessage = Msg.builder()
                        .textContent(message)
                        .name("user")
                        .role(io.agentscope.core.message.MsgRole.USER)
                        .build();
                agent.interrupt(userMessage);
                log.info("Agent [{}] 已中断（带消息）", agentName);
            } else {
                agent.interrupt();
                log.info("Agent [{}] 已中断", agentName);
            }

            updateAgentStatus(agentName, AgentState.INTERRUPTED, message);

            return InterruptResult.success("Agent interrupted successfully");

        } catch (Exception e) {
            log.error("中断 Agent 失败: {}", e.getMessage(), e);
            return InterruptResult.failure("Interrupt failed: " + e.getMessage());
        }
    }

    /**
     * 查询 Agent 执行状态
     * <p>
     * 优先查内存缓存 → 查 Redis → 查 Agent 实例存在性。
     * 不存在于任何位置的 Agent 返回 NOT_FOUND。
     * </p>
     *
     * @param agentName Agent 名称
     * @return Agent 状态（IDLE / RUNNING / INTERRUPTED / NOT_FOUND）
     */
    public AgentStatus getAgentStatus(String agentName) {
        AgentStatus cachedStatus = statusCache.get(agentName);
        if (cachedStatus != null) {
            return cachedStatus;
        }

        String statusKey = STATUS_KEY_PREFIX + agentName;
        String state = redisTemplate.opsForValue().get(statusKey);

        if (state != null) {
            AgentStatus status = new AgentStatus();
            status.setAgentName(agentName);
            status.setState(AgentState.valueOf(state));
            statusCache.put(agentName, status);
            return status;
        }

        try {
            Agent agent = agentDomainService.findAgent(agentName);
            if (agent == null) {
                return AgentStatus.notFound(agentName);
            }

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
     * <p>
     * 清除内存和 Redis 中的中断标记，使 Agent 可以继续执行。
     * 注意此方法只是清除中断状态，不重新激活 Agent 的执行线程。
     * </p>
     *
     * @param agentName Agent 名称
     * @return 恢复结果
     */
    public InterruptResult resume(String agentName) {
        log.info("恢复 Agent: {}", agentName);
        clearAgentStatus(agentName);
        return InterruptResult.success("Agent resumed, status cleared");
    }

    /**
     * 更新 Agent 状态（内存 + Redis 双写）
     */
    private void updateAgentStatus(String agentName, AgentState state, String message) {
        AgentStatus status = new AgentStatus();
        status.setAgentName(agentName);
        status.setState(state);
        status.setMessage(message);
        status.setTimestamp(System.currentTimeMillis());

        statusCache.put(agentName, status);

        String statusKey = STATUS_KEY_PREFIX + agentName;
        redisTemplate.opsForValue().set(statusKey, state.name(), STATUS_TTL);

        if (message != null) {
            String interruptKey = INTERRUPT_KEY_PREFIX + agentName;
            redisTemplate.opsForValue().set(interruptKey, message, STATUS_TTL);
        }

        log.debug("Agent 状态已更新: {} -> {}", agentName, state);
    }

    /**
     * 清除 Agent 状态（内存 + Redis 双删）
     */
    private void clearAgentStatus(String agentName) {
        statusCache.remove(agentName);
        String statusKey = STATUS_KEY_PREFIX + agentName;
        String interruptKey = INTERRUPT_KEY_PREFIX + agentName;
        redisTemplate.delete(statusKey);
        redisTemplate.delete(interruptKey);
    }

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

    public enum AgentState {
        IDLE,
        RUNNING,
        INTERRUPTED,
        NOT_FOUND
    }
}
