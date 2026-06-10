package io.yunxi.platform.agent;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.yunxi.platform.agent.service.AgentService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Agent 中断服务。
 *
 * <p>
 * 封装 AgentScope SDK 的 interrupt() API，提供：
 * - Agent 中断请求（可伴随用户消息）
 * - Agent 状态查询（内存缓存 + Redis 双层缓存）
 * - Agent 状态恢复（清除中断标记）
 * - 状态信息持久化到 Redis（支持分布式部署）
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Service
public class AgentInterruptService {

    /** Redis Key 前缀 — 中断标记，格式：agent:interrupt:{agentName} */
    private static final String INTERRUPT_KEY_PREFIX = "agent:interrupt:";

    /** Redis Key 前缀 — Agent 状态，格式：agent:status:{agentName} */
    private static final String STATUS_KEY_PREFIX = "agent:status:";

    /** 状态信息 TTL（30 分钟），超时后自动清除 */
    private static final Duration STATUS_TTL = Duration.ofMinutes(30);

    /** Agent 服务 — 查找 Agent 实例 */
    private final AgentService agentService;

    /** Redis 模板 — 用于分布式状态持久化 */
    private final StringRedisTemplate redisTemplate;

    /** Agent 状态内存缓存，减少 Redis 查询频率 */
    private final Map<String, AgentStatus> statusCache = new ConcurrentHashMap<>();

    /**
     * 构造 Agent 中断服务。
     *
     * @param agentService  Agent 服务，用于查找 Agent 实例
     * @param redisTemplate Redis 模板，用于状态持久化
     */
    public AgentInterruptService(AgentService agentService,
            StringRedisTemplate redisTemplate) {
        this.agentService = agentService;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 中断 Agent 当前执行（无伴随消息）。
     *
     * @param agentName Agent 名称
     * @return 中断结果
     */
    public InterruptResult interrupt(String agentName) {
        return interrupt(agentName, null);
    }

    /**
     * 伴随用户消息中断 Agent 当前执行。
     *
     * <p>
     * 中断时可以附带一条用户消息，该消息会被注入到 Agent 的上下文中。
     * AgentScope ReActAgent 中断后会暂停当前迭代，等待下一次调用时恢复。
     * 如果 Agent 当前没有在运行，中断请求会被忽略。
     * </p>
     *
     * @param agentName Agent 名称
     * @param message   伴随中断发送的消息（可选，用于提供上下文或指示）
     * @return 中断结果
     */
    public InterruptResult interrupt(String agentName, String message) {
        log.info("中断 Agent: {}, message: {}", agentName, message);

        try {
            // 查找 Agent 实例
            Agent agent = agentService.findAgent(agentName);
            if (agent == null) {
                return InterruptResult.failure("Agent not found: " + agentName);
            }

            if (message != null && !message.isBlank()) {
                // 构建用户消息并伴随中断发送
                Msg userMessage = Msg.builder()
                        .textContent(message)
                        .name("user")
                        .role(io.agentscope.core.message.MsgRole.USER)
                        .build();
                agent.interrupt(userMessage);
                log.info("Agent [{}] 已中断（伴随用户消息）", agentName);
            } else {
                // 直接中断，不附加消息
                agent.interrupt();
                log.info("Agent [{}] 已中断", agentName);
            }

            // 更新状态为 INTERRUPTED
            updateAgentStatus(agentName, AgentState.INTERRUPTED, message);

            return InterruptResult.success("Agent interrupted successfully");

        } catch (Exception e) {
            log.error("中断 Agent 失败: {}", e.getMessage(), e);
            return InterruptResult.failure("Interrupt failed: " + e.getMessage());
        }
    }

    /**
     * 查询 Agent 当前状态。
     *
     * <p>
     * 采用三级查找策略：
     * 1. 优先从内存缓存获取（最快）
     * 2. 缓存未命中则从 Redis 读取（分布式共享）
     * 3. Redis 中也没有则查询 Agent 实例判断状态
     * </p>
     *
     * @param agentName Agent 名称
     * @return Agent 状态（IDLE / RUNNING / INTERRUPTED / NOT_FOUND）
     */
    public AgentStatus getAgentStatus(String agentName) {
        // 第一级：内存缓存
        AgentStatus cachedStatus = statusCache.get(agentName);
        if (cachedStatus != null) {
            return cachedStatus;
        }

        // 第二级：Redis
        String statusKey = STATUS_KEY_PREFIX + agentName;
        String state = redisTemplate.opsForValue().get(statusKey);

        if (state != null) {
            AgentStatus status = new AgentStatus();
            status.setAgentName(agentName);
            status.setState(AgentState.valueOf(state));
            // 回填内存缓存
            statusCache.put(agentName, status);
            return status;
        }

        // 第三级：查询 Agent 实例
        try {
            Agent agent = agentService.findAgent(agentName);
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
     * 恢复 Agent 执行，清除中断标记。
     *
     * <p>
     * 仅清除 Redis 和内存中的状态标记，不影响 Agent 实例的实际执行。
     * 恢复后 Agent 回到 IDLE 状态，可以接受新的调用请求。
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
     * 更新 Agent 状态到内存缓存和 Redis。
     *
     * <p>
     * 双写策略：同时更新内存缓存和 Redis，确保分布式环境下状态一致。
     * 如果有伴随消息，额外存储到 Redis 的中断标记 Key 中。
     * </p>
     *
     * @param agentName Agent 名称
     * @param state     新状态
     * @param message   伴随消息（可为 null）
     */
    private void updateAgentStatus(String agentName, AgentState state, String message) {
        AgentStatus status = new AgentStatus();
        status.setAgentName(agentName);
        status.setState(state);
        status.setMessage(message);
        status.setTimestamp(System.currentTimeMillis());

        // 写入内存缓存
        statusCache.put(agentName, status);

        // 写入 Redis 状态 Key
        String statusKey = STATUS_KEY_PREFIX + agentName;
        redisTemplate.opsForValue().set(statusKey, state.name(), STATUS_TTL);

        // 如有伴随消息，写入 Redis 中断标记 Key
        if (message != null) {
            String interruptKey = INTERRUPT_KEY_PREFIX + agentName;
            redisTemplate.opsForValue().set(interruptKey, message, STATUS_TTL);
        }

        log.debug("Agent 状态已更新: {} -> {}", agentName, state);
    }

    /**
     * 清除 Agent 状态缓存和 Redis 记录。
     *
     * <p>
     * 同时删除内存缓存、Redis 状态 Key 和 Redis 中断标记 Key，
     * 确保状态完全清除。
     * </p>
     *
     * @param agentName Agent 名称
     */
    private void clearAgentStatus(String agentName) {
        // 清除内存缓存
        statusCache.remove(agentName);
        // 清除 Redis 状态和中断标记
        String statusKey = STATUS_KEY_PREFIX + agentName;
        String interruptKey = INTERRUPT_KEY_PREFIX + agentName;
        redisTemplate.delete(statusKey);
        redisTemplate.delete(interruptKey);
    }

    /**
     * 中断操作结果。
     *
     * <p>
     * 封装中断/恢复操作的执行结果，包含成功标志和描述信息。
     * </p>
     */
    @Data
    public static class InterruptResult {
        /** 操作是否成功 */
        private boolean success;
        /** 结果描述信息 */
        private String message;

        /**
         * 私有构造函数。
         *
         * @param success 是否成功
         * @param message 描述信息
         */
        private InterruptResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        /**
         * 创建成功结果。
         *
         * @param message 成功描述
         * @return 成功的 InterruptResult
         */
        public static InterruptResult success(String message) {
            return new InterruptResult(true, message);
        }

        /**
         * 创建失败结果。
         *
         * @param message 失败描述
         * @return 失败的 InterruptResult
         */
        public static InterruptResult failure(String message) {
            return new InterruptResult(false, message);
        }
    }

    /**
     * Agent 状态信息。
     *
     * <p>
     * 包含 Agent 名称、当前状态、伴随消息和状态更新时间戳。
     * </p>
     */
    @Data
    public static class AgentStatus {
        /** Agent 名称 */
        private String agentName;
        /** Agent 当前状态 */
        private AgentState state;
        /** 伴随消息（如中断时用户附加的说明） */
        private String message;
        /** 状态更新时间戳（毫秒） */
        private Long timestamp;

        /**
         * 创建 NOT_FOUND 状态的 AgentStatus。
         *
         * @param agentName Agent 名称
         * @return 状态为 NOT_FOUND 的 AgentStatus
         */
        public static AgentStatus notFound(String agentName) {
            AgentStatus status = new AgentStatus();
            status.setAgentName(agentName);
            status.setState(AgentState.NOT_FOUND);
            return status;
        }
    }

    /**
     * Agent 状态枚举。
     *
     * <p>
     * IDLE：空闲，未在执行任务
     * RUNNING：运行中，正在处理请求
     * INTERRUPTED：已中断，暂停执行等待恢复
     * NOT_FOUND：Agent 实例不存在
     * </p>
     */
    public enum AgentState {
        /** 空闲状态 */
        IDLE,
        /** 运行中状态 */
        RUNNING,
        /** 已中断状态 */
        INTERRUPTED,
        /** Agent 未找到 */
        NOT_FOUND
    }
}
