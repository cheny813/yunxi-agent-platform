package io.yunxi.platform.muse.sandbox;

import java.util.List;

/**
 * 沙箱执行器抽象。MUSE 在评估技能时需要在隔离环境里跑测试脚本，
 * 这一能力 AgentScope 不提供，是 MUSE 的核心新增之一。
 *
 * <p>实现：{@link LocalSubprocessSandboxRunner}（本机子进程，仅超时隔离）、
 * {@link DockerSandboxRunner}（容器隔离，推荐生产）。</p>
 */
public interface SandboxRunner {

    /**
     * 在沙箱中执行命令。
     *
     * @param command  待执行命令（如 ["pytest", "-q"]）
     * @param workDir  工作目录（技能目录，绝对路径字符串）
     * @param timeoutMs 超时（毫秒）
     * @return 执行结果
     */
    SandboxRunResult run(List<String> command, String workDir, long timeoutMs);

    /** 沙箱单次执行结果。 */
    class SandboxRunResult {
        private int exitCode;
        private String stdout = "";
        private String stderr = "";
        private boolean timedOut;

        public SandboxRunResult() {}

        public SandboxRunResult(int exitCode, String stdout, String stderr, boolean timedOut) {
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.timedOut = timedOut;
        }

        public int getExitCode() { return exitCode; }
        public void setExitCode(int exitCode) { this.exitCode = exitCode; }
        public String getStdout() { return stdout; }
        public void setStdout(String stdout) { this.stdout = stdout; }
        public String getStderr() { return stderr; }
        public void setStderr(String stderr) { this.stderr = stderr; }
        public boolean isTimedOut() { return timedOut; }
        public void setTimedOut(boolean timedOut) { this.timedOut = timedOut; }
    }
}
