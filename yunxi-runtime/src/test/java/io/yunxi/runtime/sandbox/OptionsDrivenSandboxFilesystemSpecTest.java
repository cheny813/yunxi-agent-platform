package io.yunxi.runtime.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import org.junit.jupiter.api.Test;

/**
 * 针对 {@link OptionsDrivenSandboxFilesystemSpec} 的聚焦单测：验证 4 个框架工厂无参抽象方法
 * 正确返回构造期注入的对象，且隔离粒度经基类 fluent setter 生效（不重新实现执行器）。
 */
class OptionsDrivenSandboxFilesystemSpecTest {

    @Test
    void factoriesReturnInjectedObjects() {
        DockerSandboxClientOptions clientOptions = new DockerSandboxClientOptions();
        clientOptions.setImage("ubuntu:22.04");
        WorkspaceSpec workspaceSpec = new WorkspaceSpec();
        workspaceSpec.setRoot("/workspace");
        SandboxSnapshotSpec snapshotSpec = new NoopSnapshotSpec();
        IsolationScope isolationScope = IsolationScope.SESSION;
        SandboxExecutionGuard executionGuard = SandboxExecutionGuard.noop();

        OptionsDrivenSandboxFilesystemSpec spec = new OptionsDrivenSandboxFilesystemSpec(
                clientOptions, workspaceSpec, snapshotSpec, isolationScope, executionGuard);

        assertSame(clientOptions, spec.clientOptions(), "clientOptions 工厂须返回注入的容器选项");
        assertSame(workspaceSpec, spec.workspaceSpec(), "workspaceSpec 工厂须返回注入的工作区说明");
        assertSame(snapshotSpec, spec.snapshotSpec(), "snapshotSpec 工厂须返回注入的快照策略");
        assertNotNull(spec.createClient(), "createClient 须委托框架容器执行器返回非 null 客户端");
        assertEquals(IsolationScope.SESSION, spec.getIsolationScope(), "隔离粒度须经 fluent setter 生效");
    }
}
