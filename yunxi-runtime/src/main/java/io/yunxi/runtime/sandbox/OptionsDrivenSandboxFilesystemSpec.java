package io.yunxi.runtime.sandbox;

import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

/**
 * 声明式配置驱动的薄适配层（产品平面 seam，将声明式 sandbox 配置翻译为框架执行器）。
 *
 * <p>只消费 AgentScope-Java 的 {@link SandboxClient} / {@link SandboxFilesystemSpec}，不重新实现执行器
 * （框架已提供同名执行器原语，yunxi 侧不平行实现，仅做声明式配置到选项的翻译）。框架
 * {@code SandboxFilesystemSpec} 的 4 个工厂被设计为无参抽象方法，故本类在构造期接收
 * 已解析的 options / workspace / snapshot / 隔离粒度 / 闸门，并直接委托框架容器执行器，零代码获得隔离执行。
 * yaml 到选项的映射由 {@code io.yunxi.runtime.AgentConfigurer} 负责。</p>
 */
public final class OptionsDrivenSandboxFilesystemSpec extends SandboxFilesystemSpec {

    private final SandboxClientOptions clientOptions;
    private final WorkspaceSpec workspaceSpec;
    private final SandboxSnapshotSpec snapshotSpec;

    public OptionsDrivenSandboxFilesystemSpec(
            SandboxClientOptions clientOptions,
            WorkspaceSpec workspaceSpec,
            SandboxSnapshotSpec snapshotSpec,
            IsolationScope isolationScope,
            SandboxExecutionGuard executionGuard) {
        this.clientOptions = clientOptions;
        this.workspaceSpec = workspaceSpec;
        this.snapshotSpec = snapshotSpec;
        // 基类 fluent setter：隔离粒度 / 快照覆盖 / 执行闸门，供 HarnessAgent.Builder.filesystem 装配期读取。
        this.isolationScope(isolationScope);
        this.snapshotSpec(snapshotSpec);
        this.executionGuard(executionGuard);
    }

    @Override
    protected SandboxClient<?> createClient() {
        return clientOptions.createClient();
    }

    @Override
    protected SandboxClientOptions clientOptions() {
        return clientOptions;
    }

    @Override
    protected SandboxSnapshotSpec snapshotSpec() {
        return snapshotSpec;
    }

    @Override
    protected WorkspaceSpec workspaceSpec() {
        return workspaceSpec;
    }
}
