package io.yunxi.platform.lifecycle;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;

/**
 * 记忆后台任务回收桥接器。
 *
 * <p>Agent 关闭后，记忆系统的 flush / consolidation 等后台模型调用可能仍在飞行中：这类任务
 * 在响应返回后才异步派发，不与 Agent 生命周期绑定，若不主动取消，底层 HTTP 连接与 in-flight
 * 槽位会一直占用到 JVM 退出。AgentScope-Java 自 2.0.4 起提供
 * {@code MemoryBackgroundTasks.cancelAll()}，用于按持有者取消任务并释放槽位；2.0.3 及更早
 * 版本没有该 API。
 *
 * <p>本类在启动时反射探测该 API：存在则调用，获得完整的取消能力；不存在则退化为"仅等待
 * 静默、不主动取消"，保证依赖官方已发布版本时同样可以编译和运行。探测结果只在类加载时
 * 计算一次，运行时无额外开销。
 *
 * <p>删除条件：当项目最低支持的 AgentScope-Java 版本在编译期即提供 {@code cancelAll()} 时，
 * 删除本类，改回 {@link AgentscopeLifecycleManager} 中的直接调用即可。
 *
 * @author yunxi-agent-platform
 */
public final class MemoryBackgroundTaskReaper {

    private static final Logger log = LoggerFactory.getLogger(MemoryBackgroundTaskReaper.class);

    private static final Method CANCEL_ALL = resolveCancelAll();

    private MemoryBackgroundTaskReaper() {
    }

    /**
     * 当前依赖的 AgentScope-Java 是否支持主动取消记忆后台任务。
     *
     * @return 支持返回 true；运行在 2.0.3 及更早版本上返回 false
     */
    public static boolean isCancellable() {
        return CANCEL_ALL != null;
    }

    /**
     * 等待记忆后台任务静默，并在支持的前提下取消仍未结束的任务。
     *
     * @param timeout 最长等待时间
     * @param unit    等待时间单位
     */
    public static void awaitQuiescenceAndCancel(long timeout, TimeUnit unit) {
        try {
            MemoryBackgroundTasks.awaitQuiescence(timeout, unit);
        } catch (LinkageError e) {
            log.warn("MemoryBackgroundTasks 不可用，跳过后台任务回收: {}", e.getMessage());
            return;
        } catch (Exception e) {
            log.warn("等待记忆后台任务静默失败: {}", e.getMessage());
        }

        if (CANCEL_ALL == null) {
            log.warn("当前 AgentScope-Java 版本不支持主动取消记忆后台任务（需 >= 2.0.4），"
                    + "关闭后遗留的后台模型调用将等待其自然结束");
            return;
        }

        try {
            CANCEL_ALL.invoke(null);
            log.info("已取消关闭时仍在飞行的记忆后台任务");
        } catch (Exception e) {
            log.warn("取消记忆后台任务失败: {}", e.getMessage());
        }
    }

    private static Method resolveCancelAll() {
        try {
            Method method = MemoryBackgroundTasks.class.getMethod("cancelAll");
            log.debug("检测到 MemoryBackgroundTasks.cancelAll()，启用记忆后台任务主动取消");
            return method;
        } catch (NoSuchMethodException e) {
            return null;
        } catch (LinkageError e) {
            return null;
        }
    }
}
