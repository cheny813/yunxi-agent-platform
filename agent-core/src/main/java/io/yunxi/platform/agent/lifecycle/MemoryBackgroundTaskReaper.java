package io.yunxi.platform.agent.lifecycle;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;

/**
 * 记忆后台任务回收桥接器。
 *
 * <p>Agent 关闭后，记忆系统的 flush / consolidation 等后台模型调用可能仍在飞行中：这类任务
 * 在响应返回后才异步派发，不与 Agent 生命周期绑定，若不等待其结束，底层 HTTP 连接与 in-flight
 * 槽位会一直占用到 JVM 退出。
 *
 * <p>底层框架 AgentScope-Java 2.0.4 的 {@code MemoryBackgroundTasks} 提供
 * {@code begin()} / {@code end()} / {@code awaitQuiescence()}，本桥接器在 Agent 关闭时直接调用
 * 框架的 {@code awaitQuiescence} 等待仍在飞行的后台模型调用自然结束。框架在 2.0.4 版本尚未提供
 * 主动取消能力，因此采用等待静默的方式回收，而非自行实现取消逻辑。
 */
public final class MemoryBackgroundTaskReaper {

    private static final Logger log = LoggerFactory.getLogger(MemoryBackgroundTaskReaper.class);

    private MemoryBackgroundTaskReaper() {
    }

    /**
     * 等待记忆后台任务静默。
     *
     * @param timeout 最长等待时间
     * @param unit    等待时间单位
     */
    public static void awaitQuiescence(long timeout, TimeUnit unit) {
        try {
            MemoryBackgroundTasks.awaitQuiescence(timeout, unit);
        } catch (LinkageError e) {
            log.warn("MemoryBackgroundTasks 不可用，跳过后台任务回收: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("等待记忆后台任务静默失败: {}", e.getMessage());
        }
    }
}
