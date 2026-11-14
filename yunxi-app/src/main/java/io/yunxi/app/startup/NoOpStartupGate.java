package io.yunxi.app.startup;

/** 关闭启动闸门（yunxi.startup-gate.enabled=false）时的恒放行实现。 */
public class NoOpStartupGate implements StartupGate {

    @Override
    public boolean tryAcquire() {
        return true;
    }

    @Override
    public void release() {
        // no-op
    }
}
