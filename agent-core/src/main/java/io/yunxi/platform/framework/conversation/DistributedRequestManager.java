package io.yunxi.platform.framework.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 分布式请求管理服务（基于 Redis）
 *
 * <p>
 * 使用 Redis 存储请求状态，支持集群部署。
 * 解决本地内存存储无法跨实例共享的问题。
 * </p>
 *
 * <p>
 * <b>核心特性</b>：
 * <ul>
 * <li>分布式请求状态管理</li>
 * <li>支持跨实例取消请求</li>
 * <li>自动过期（TTL）</li>
 * <li>集群安全的活跃请求统计</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Service
public class DistributedRequestManager {

    /** Redis 模板 */
    private final RedisTemplate<String, Object> redisTemplate;
    /** JSON 序列化工具 */
    private final ObjectMapper objectMapper;

    /**
     * Redis Key 前缀
     */
    private static final String REQUEST_PREFIX = "agent:request:";
    /** 取消令牌 Key 前缀 */
    private static final String CANCEL_TOKEN_PREFIX = "agent:cancel:";
    /** 活跃请求计数 Key */
    private static final String ACTIVE_COUNT_KEY = "agent:requests:active-count";

    /**
     * 默认超时时间（5分钟）
     */
    private static final long DEFAULT_TIMEOUT_SECONDS = 5 * 60;

    /**
     * 请求信息（Redis 存储）
     */
    @Data
    public static class RequestInfo {
        /** 请求 ID */
        private String requestId;
        /** 取消令牌 */
        private String cancelToken;
        /** Agent 名称 */
        private String agentName;
        /** 创建时间 */
        private long createTime;
        /** 是否已取消 */
        private boolean cancelled;

        /**
         * 默认构造方法
         */
        public RequestInfo() {
        }

        /**
         * 构造请求信息
         *
         * @param requestId   请求 ID
         * @param cancelToken 取消令牌
         * @param agentName   Agent 名称
         */
        public RequestInfo(String requestId, String cancelToken, String agentName) {
            this.requestId = requestId;
            this.cancelToken = cancelToken;
            this.agentName = agentName;
            this.createTime = System.currentTimeMillis();
            this.cancelled = false;
        }

        /**
         * 检查请求是否超时
         *
         * @param timeoutMs 超时时间（毫秒）
         * @return 如果超时返回 true
         */
        public boolean isExpired(long timeoutMs) {
            return System.currentTimeMillis() - createTime > timeoutMs;
        }
    }

    /**
     * 构造分布式请求管理服务
     *
     * @param redisTemplate Redis 模板
     */
    public DistributedRequestManager(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        log.info("初始化分布式请求管理服务（基于 Redis）");
    }

    /**
     * 生成唯一的请求ID
     *
     * @return 请求 ID 字符串
     */
    public String generateRequestId() {
        return "req-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 生成唯一的取消令牌
     *
     * @return 取消令牌字符串
     */
    public String generateCancelToken() {
        return "cancel-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 注册一个新的请求
     *
     * @param agentName Agent 名称
     * @return 请求信息（包含 requestId 和 cancelToken）
     */
    public RequestInfo registerRequest(String agentName) {
        String requestId = generateRequestId();
        String cancelToken = generateCancelToken();
        RequestInfo requestInfo = new RequestInfo(requestId, cancelToken, agentName);

        // 存储 RequestInfo（带 TTL）
        String requestKey = REQUEST_PREFIX + requestId;
        redisTemplate.opsForValue().set(requestKey, requestInfo, DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // 存储取消令牌到请求ID的映射（带 TTL）
        String cancelKey = CANCEL_TOKEN_PREFIX + cancelToken;
        redisTemplate.opsForValue().set(cancelKey, requestId, DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // 增加活跃请求计数
        redisTemplate.opsForValue().increment(ACTIVE_COUNT_KEY);

        log.debug("注册请求: requestId={}, cancelToken={}, agentName={}", requestId, cancelToken, agentName);
        return requestInfo;
    }

    /**
     * 使用指定的取消令牌注册请求
     *
     * @param agentName   Agent 名称
     * @param cancelToken 客户端提供的取消令牌
     * @return 请求信息
     */
    public RequestInfo registerRequestWithToken(String agentName, String cancelToken) {
        String requestId = generateRequestId();
        RequestInfo requestInfo = new RequestInfo(requestId, cancelToken, agentName);

        // 存储 RequestInfo（带 TTL）
        String requestKey = REQUEST_PREFIX + requestId;
        redisTemplate.opsForValue().set(requestKey, requestInfo, DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // 存储取消令牌到请求ID的映射（带 TTL）
        String cancelKey = CANCEL_TOKEN_PREFIX + cancelToken;
        redisTemplate.opsForValue().set(cancelKey, requestId, DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // 增加活跃请求计数
        redisTemplate.opsForValue().increment(ACTIVE_COUNT_KEY);

        log.debug("注册请求（带自定义令牌）: requestId={}, cancelToken={}, agentName={}",
                requestId, cancelToken, agentName);
        return requestInfo;
    }

    /**
     * 注销请求
     *
     * @param requestId 请求 ID
     */
    public void unregisterRequest(String requestId) {
        String requestKey = REQUEST_PREFIX + requestId;
        RequestInfo requestInfo = (RequestInfo) redisTemplate.opsForValue().get(requestKey);

        if (requestInfo != null) {
            // 删除取消令牌映射
            String cancelKey = CANCEL_TOKEN_PREFIX + requestInfo.getCancelToken();
            redisTemplate.delete(cancelKey);

            // 删除请求信息
            redisTemplate.delete(requestKey);

            // 减少活跃请求计数
            redisTemplate.opsForValue().decrement(ACTIVE_COUNT_KEY);

            log.debug("注销请求: requestId={}", requestId);
        }
    }

    /**
     * 取消请求（分布式）
     *
     * @param cancelToken 取消令牌
     * @return true 如果取消成功，false 如果请求不存在或已完成
     */
    public boolean cancelRequest(String cancelToken) {
        String cancelKey = CANCEL_TOKEN_PREFIX + cancelToken;
        String requestId = (String) redisTemplate.opsForValue().get(cancelKey);

        if (requestId == null) {
            log.warn("取消请求失败：取消令牌不存在: {}", cancelToken);
            return false;
        }

        String requestKey = REQUEST_PREFIX + requestId;
        RequestInfo requestInfo = (RequestInfo) redisTemplate.opsForValue().get(requestKey);

        if (requestInfo == null) {
            log.warn("取消请求失败：请求不存在: {}", requestId);
            return false;
        }

        if (requestInfo.isCancelled()) {
            log.warn("取消请求失败：请求已被取消: {}", requestId);
            return false;
        }

        // 标记请求为已取消
        requestInfo.setCancelled(true);
        redisTemplate.opsForValue().set(requestKey, requestInfo, DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        log.info("取消请求成功: requestId={}, cancelToken={}", requestId, cancelToken);
        return true;
    }

    /**
     * 检查请求是否已被取消
     *
     * @param requestId 请求 ID
     * @return true 如果请求已被取消
     */
    public boolean isRequestCancelled(String requestId) {
        String requestKey = REQUEST_PREFIX + requestId;
        RequestInfo requestInfo = (RequestInfo) redisTemplate.opsForValue().get(requestKey);
        return requestInfo != null && requestInfo.isCancelled();
    }

    /**
     * 检查请求是否仍然活跃
     *
     * @param requestId 请求 ID
     * @return true 如果请求仍然活跃
     */
    public boolean isRequestActive(String requestId) {
        String requestKey = REQUEST_PREFIX + requestId;
        RequestInfo requestInfo = (RequestInfo) redisTemplate.opsForValue().get(requestKey);
        return requestInfo != null && !requestInfo.isCancelled();
    }

    /**
     * 获取请求信息
     *
     * @param requestId 请求 ID
     * @return 请求信息，如果不存在返回 null
     */
    public RequestInfo getRequestInfo(String requestId) {
        String requestKey = REQUEST_PREFIX + requestId;
        return (RequestInfo) redisTemplate.opsForValue().get(requestKey);
    }

    /**
     * 获取当前活跃请求数量（集群级别）
     *
     * @return 活跃请求数量
     */
    public int getActiveRequestCount() {
        Long count = (Long) redisTemplate.opsForValue().get(ACTIVE_COUNT_KEY);
        return count != null ? count.intValue() : 0;
    }

    /**
     * 获取所有活跃请求（用于监控）
     *
     * @return 活跃请求列表
     */
    public List<RequestInfo> getAllActiveRequests() {
        String pattern = REQUEST_PREFIX + "*";
        Set<String> keys = redisTemplate.keys(pattern);

        if (keys == null || keys.isEmpty()) {
            return List.of();
        }

        return keys.stream()
                .map(key -> (RequestInfo) redisTemplate.opsForValue().get(key))
                .filter(info -> info != null && !info.isCancelled())
                .toList();
    }

    /**
     * 创建一个可以监听取消事件的 Flux（分布式）
     *
     * @param requestId 请求 ID
     * @return Flux，当请求被取消时会发出信号
     */
    public Flux<Void> createCancellationSignal(String requestId) {
        return Flux.interval(Duration.ofMillis(100))
                .takeUntil(tick -> {
                    boolean cancelled = isRequestCancelled(requestId);
                    if (cancelled) {
                        log.info("检测到请求被取消: {}", requestId);
                    }
                    return cancelled;
                })
                .flatMap(tick -> Flux.empty());
    }

    /**
     * 创建请求取消监听器包装器（分布式）
     *
     * @param requestId    请求 ID
     * @param originalFlux 原始的 Flux
     * @return 包装后的 Flux
     */
    public <T> Flux<T> withCancellationListener(String requestId, Flux<T> originalFlux) {
        return Flux.merge(
                originalFlux,
                createCancellationSignal(requestId)
                        .flatMap(ignore -> Flux.error(new RequestCancelledException("请求被客户端取消"))))
                .takeUntil(signal -> {
                    if (signal instanceof Throwable) {
                        return true;
                    }
                    return false;
                });
    }

    /**
     * 请求被取消异常
     */
    public static class RequestCancelledException extends RuntimeException {
        /**
         * 构造请求取消异常
         *
         * @param message 异常信息
         */
        public RequestCancelledException(String message) {
            super(message);
        }
    }

    /**
     * 设置自定义超时时间
     *
     * @param requestId 请求 ID
     * @param seconds   超时时间（秒）
     */
    public void setTimeout(String requestId, long seconds) {
        String requestKey = REQUEST_PREFIX + requestId;
        RequestInfo requestInfo = (RequestInfo) redisTemplate.opsForValue().get(requestKey);

        if (requestInfo != null) {
            String cancelKey = CANCEL_TOKEN_PREFIX + requestInfo.getCancelToken();
            redisTemplate.expire(requestKey, seconds, TimeUnit.SECONDS);
            redisTemplate.expire(cancelKey, seconds, TimeUnit.SECONDS);
            log.debug("设置请求超时: requestId={}, seconds={}", requestId, seconds);
        }
    }

    /**
     * 获取集群中所有实例的活跃请求总数
     *
     * @return 活跃请求数量
     */
    public int getClusterActiveRequestCount() {
        return getActiveRequestCount();
    }
}
