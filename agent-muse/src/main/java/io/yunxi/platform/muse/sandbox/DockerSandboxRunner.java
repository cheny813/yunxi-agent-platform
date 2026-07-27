package io.yunxi.platform.muse.sandbox;

import io.yunxi.platform.muse.config.EvolutionConfig.SandboxConfig;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Docker 容器沙箱。MUSE 评估在隔离容器里跑测试，限制内存/CPU/网络。
 *
 * <p>对应的镜像由 {@code docker/sandbox.Dockerfile} 构建（镜像名配置在
 * {@code yunxi.muse.sandbox.image}）。</p>
 *
 * <p>运行要求：部署节点需安装 docker 且当前用户可免 sudo 执行
 * {@code docker run}。Windows 本地开发应切到 {@link LocalSubprocessSandboxRunner}
 * （profile {@code !muse-docker} 自动激活）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "yunxi.muse.enabled", havingValue = "true")
@Profile("muse-docker")
public class DockerSandboxRunner implements SandboxRunner {

    private final SandboxConfig cfg;

    public DockerSandboxRunner(SandboxConfig cfg) {
        this.cfg = cfg;
    }

    /** {@inheritDoc} */
    @Override
    public SandboxRunResult run(List<String> command, String workDir, long timeoutMs) {
        List<String> docker = new ArrayList<>();
        docker.add("docker");
        docker.add("run");
        docker.add("--rm");
        docker.add("-w");
        docker.add("/work");
        docker.add("-v");
        docker.add(workDir + ":/work");
        if (cfg.isNetworkDisabled()) {
            docker.add("--network");
            docker.add("none");
        }
        if (cfg.getMemory() != null) {
            docker.add("--memory");
            docker.add(cfg.getMemory());
        }
        if (cfg.getCpus() != null) {
            docker.add("--cpus");
            docker.add(cfg.getCpus());
        }
        docker.add(cfg.getImage());
        docker.addAll(command);

        ProcessBuilder pb = new ProcessBuilder(docker);
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
            return new SandboxRunResult(-1, "", "docker IO error: " + e.getMessage(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SandboxRunResult(-1, "", "INTERRUPTED: " + e.getMessage(), false);
        }
    }
}
