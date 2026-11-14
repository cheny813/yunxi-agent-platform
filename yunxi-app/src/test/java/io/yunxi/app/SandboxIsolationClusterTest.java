package io.yunxi.app;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yunxi.intelligence.muse.sandbox.DockerSandboxRunner;
import io.yunxi.intelligence.muse.sandbox.LocalSubprocessSandboxRunner;
import io.yunxi.intelligence.muse.sandbox.SandboxRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * 验证沙箱隔离随部署形态切换：{@code yunxi.cluster.mode=cluster}（无论是否显式 muse-docker profile）
 * 必须装配 Docker 强隔离沙箱；纯配置驱动，无需改程序源码。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "yunxi.security.user-name=admin",
        "yunxi.security.user-password=test-secret",
        "yunxi.cluster.mode=cluster"
})
class SandboxIsolationClusterTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void clusterModeActivatesDockerSandbox() {
        SandboxRunner runner = context.getBean(SandboxRunner.class);
        assertNotNull(runner, "cluster 模式必须注册 SandboxRunner");
        assertTrue(runner instanceof DockerSandboxRunner,
                "cluster 模式应装配 DockerSandboxRunner（强隔离），实际：" + runner.getClass().getName());
        assertTrue(!(runner instanceof LocalSubprocessSandboxRunner),
                "cluster 模式不得退化为本机子进程沙箱");
    }
}
