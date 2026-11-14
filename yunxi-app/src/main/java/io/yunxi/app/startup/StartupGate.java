package io.yunxi.app.startup;

/**
 * 跨实例启动协调闸门。多个 yunxi 实例并发启动时，仅允许有限个 leader 实例全量布建，
 * 其余实例跳过本地全量加载（依赖 leader 预热），以此降低 N 实例并发启动风暴。
 */
public interface StartupGate {

    /**
     * 尝试获取 leader 资格。
     *
     * @return {@code true} 表示本实例可继续全量布建；{@code false} 表示应跳过本地加载（其他实例正在布建）
     */
    boolean tryAcquire();

    /**
     * 布建完成后释放 leader 资格，允许后续实例在需要时自行布建（正确性不受影响，仅降低并发启动风暴）。
     */
    void release();
}
