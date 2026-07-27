package io.yunxi.platform.muse.sandbox;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 本机子进程沙箱（仅超时隔离）。
 *
 * <p>适合本地 PoC / Windows 开发环境。生产环境请使用 {@link DockerSandboxRunner}，
 * 因为本机模式无法限制内存/CPU，且 Windows 下受限。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "yunxi.muse.enabled", havingValue = "true")
@Profile("!muse-docker")
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
