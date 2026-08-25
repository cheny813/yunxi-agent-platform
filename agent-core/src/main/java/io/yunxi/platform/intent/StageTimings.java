package io.yunxi.platform.intent;

/**
 * 各阶段耗时（毫秒）。
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public record StageTimings(
        long nerMs,
        long rewriteMs,
        long classifyMs,
        long mappingMs,
        long totalMs) {
}
