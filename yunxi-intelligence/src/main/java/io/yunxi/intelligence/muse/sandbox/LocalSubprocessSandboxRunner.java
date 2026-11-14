package io.yunxi.intelligence.muse.sandbox;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * 本机子进程沙箱（仅超时隔离）。
 *
 * <p>适合本地 PoC / Windows 开发环境，以及 standalone 部署默认。生产集群
 * （{@code yunxi.cluster.mode=cluster}）会自动切到 {@link DockerSandboxRunner} 强隔离，
 * 因为本机模式无法限制内存/CPU，且 Windows 下受限。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "yunxi.muse.enabled", havingValue = "true", matchIfMissing = true)
@Conditional(LocalSandboxCondition.class)
public class LocalSubprocessSandboxRunner implements SandboxRunner {

    @Override
    public SandboxRunResult run(List<String> command, String workDir, long timeoutMs) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(Path.of(workDir).toFile());
        pb.redirectErrorStream(false);
        try {
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new SandboxRunResult(-1, "", "TIMEOUT after " + timeoutMs + "ms", true);
            }
            String out = new String(process.getInputStream().readAllBytes());
            String err = new String(process.getErrorStream().readAllBytes());
            return new SandboxRunResult(process.exitValue(), out, err, false);
        } catch (IOException e) {
            return new SandboxRunResult(-1, "", "IO error: " + e.getMessage(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SandboxRunResult(-1, "", "INTERRUPTED: " + e.getMessage(), false);
        }
    }
}
