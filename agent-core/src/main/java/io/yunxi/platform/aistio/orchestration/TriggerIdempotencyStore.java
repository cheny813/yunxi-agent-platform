package io.yunxi.platform.aistio.orchestration;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 触发幂等键存储（业务请求级幂等，避免超时重试重复起 run）。
 *
 * <p>服务端的 {@code Idempotency-Key} 是 <b>delivery 级</b>去重键，不是业务请求级：
 * 只有 yunxi 自己记住「这个业务请求第一次用的是哪个 key」，重试时才能复用，否则任何一层
 * 超时重试都会重复起一个 run（服务端无从判定"这是同一个业务请求"）。
 *
 * <p><b>持久化尚未落地</b>：设计要求本地落库（MySQL 表 {@code yunxi_orchestration_trigger}），
 * 当前默认实现是进程内内存 —— 进程重启后映射丢失，仅满足单进程内的重试语义。
 * 后续接 MySQL 时实现本接口替换即可，调用方无需改动。
 */
public interface TriggerIdempotencyStore {

    /**
     * 取该业务请求的幂等键；首次调用生成并记住，后续调用返回同一个值。
     */
    String idempotencyKeyFor(String bizRequestId);

    /** 进程内内存实现（默认；重启即失效，适用于单实例与非跨重启重试场景） */
    final class InMemory implements TriggerIdempotencyStore {

        private final Map<String, String> keys = new ConcurrentHashMap<>();

        @Override
        public String idempotencyKeyFor(String bizRequestId) {
            if (bizRequestId == null || bizRequestId.isBlank()) {
                throw new IllegalArgumentException("bizRequestId is required");
            }
            return keys.computeIfAbsent(bizRequestId, k -> UUID.randomUUID().toString());
        }
    }
}
