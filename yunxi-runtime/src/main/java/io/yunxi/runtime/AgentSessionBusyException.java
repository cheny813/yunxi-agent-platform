package io.yunxi.runtime;

/**
 * 会话轮次被拒绝：同一 (userId, sessionId) 的并发入站在 Admit 段被 {@code SessionTurnGate} 拦截。
 *
 * <p>两种成因：
 * <ul>
 *   <li>{@code interrupted=true}：取锁线程被中断；</li>
 *   <li>{@code interrupted=false}：锁已被其他实例/线程持有（多副本部署下表示另一副本正在处理该会话）。</li>
 * </ul>
 * 该异常用于让上游（入口层 / Settle）区分「会话忙、稍后重试」与「执行失败」，而非盲目双跑。
 */
public class AgentSessionBusyException extends RuntimeException {

    private final String turnKey;
    private final boolean interrupted;

    public AgentSessionBusyException(String turnKey, boolean interrupted) {
        super(interrupted
                ? "session turn interrupted while acquiring gate: " + turnKey
                : "session turn busy; another replica holds the gate for: " + turnKey);
        this.turnKey = turnKey;
        this.interrupted = interrupted;
    }

    public String turnKey() {
        return turnKey;
    }

    public boolean isInterrupted() {
        return interrupted;
    }
}
