package io.yunxi.platform.framework.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工具熔断器
 *
 * <p>
 * 当同一工具连续失败达到阈值时，自动熔断，后续调用直接返回失败信息，
 * 避免在 ReAct 循环中反复调用已确认不可用的工具，浪费 Token 和时间。
 * </p>
 *
 * <p>
 * 熔断恢复：经过指定时间后自动半开，允许重试。
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Component
public class ToolCircuitBreaker {

    /** 连续失败次数阈值（达到此值触发熔断） */
    private static final int FAILURE_THRESHOLD = 2;

    /** 熔断恢复时间（毫秒） */
    private static final long RECOVERY_TIMEOUT_MS = 60_000;

    /** 工具名称 → 熔断状态 */
    private final Map<String, CircuitState> states = new ConcurrentHashMap<>();

    /**
     * 记录工具调用成功
     *
     * @param toolName 工具名称
     */
    public void recordSuccess(String toolName) {
        CircuitState state = states.get(toolName);
        if (state != null) {
            state.failureCount.set(0);
            state.open.set(false);
        }
    }

    /**
     * 记录工具调用失败
     *
     * @param toolName 工具名称
     */
    public void recordFailure(String toolName) {
        CircuitState state = states.computeIfAbsent(toolName, k -> new CircuitState());
        int count = state.failureCount.incrementAndGet();
        if (count >= FAILURE_THRESHOLD && !state.open.get()) {
            state.open.set(true);
            state.openedAt.set(System.currentTimeMillis());
            log.warn("工具 [{}] 连续失败 {} 次，已熔断，{}秒后自动恢复", toolName, count, RECOVERY_TIMEOUT_MS / 1000);
        }
    }

    /**
     * 检查工具是否被熔断
     *
     * @param toolName 工具名称
     * @return true 表示工具已熔断，应跳过调用
     */
    public boolean isCircuitOpen(String toolName) {
        CircuitState state = states.get(toolName);
        if (state == null || !state.open.get()) {
            return false;
        }

        // 检查是否到达恢复时间
        long elapsed = System.currentTimeMillis() - state.openedAt.get();
        if (elapsed >= RECOVERY_TIMEOUT_MS) {
            // 半开：允许一次尝试
            state.open.set(false);
            state.failureCount.set(0);
            log.info("工具 [{}] 熔断恢复，允许重试", toolName);
            return false;
        }

        return true;
    }

    /**
     * 获取熔断状态信息（用于调试）
     *
     * @param toolName 工具名称
     * @return 状态描述
     */
    public String getStatus(String toolName) {
        CircuitState state = states.get(toolName);
        if (state == null) {
            return "正常";
        }
        if (state.open.get()) {
            long remaining = RECOVERY_TIMEOUT_MS - (System.currentTimeMillis() - state.openedAt.get());
            return String.format("熔断中（连续失败%d次，%d秒后恢复）", state.failureCount.get(), remaining / 1000);
        }
        return String.format("正常（连续失败%d次）", state.failureCount.get());
    }

    /** 熔断状态 */
    private static class CircuitState {
        final AtomicInteger failureCount = new AtomicInteger(0);
        final AtomicLong openedAt = new AtomicLong(0);
        final java.util.concurrent.atomic.AtomicBoolean open = new java.util.concurrent.atomic.AtomicBoolean(false);
    }
}
