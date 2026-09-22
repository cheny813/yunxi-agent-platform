package io.yunxi.platform.aistio.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.agentscope.core.tool.Toolkit;
import io.yunxi.platform.agent.service.AgentService;
import io.yunxi.platform.aistio.orchestration.workflow.WorkflowRunStateStore;
import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.config.MountBodyConfig;
import io.yunxi.platform.shared.config.MountConfig;
import io.yunxi.platform.shared.config.OrchestrationConfig;

class MountFactoryTest {

    @Test
    void workflowMountCreatesGroupWithoutThrowing() {
        AgentService agentService = mock(AgentService.class);
        OrchestrationTrigger trigger = mock(OrchestrationTrigger.class);
        WorkflowRunStateStore store = new WorkflowRunStateStore.InMemory();

        MountFactory factory = new MountFactory(agentService, trigger, store);
        Toolkit toolkit = new Toolkit();

        AgentDefinition def = new AgentDefinition();
        def.setName("host");
        OrchestrationConfig orch = new OrchestrationConfig();
        orch.setPattern("single");
        MountConfig mount = new MountConfig();
        mount.setName("wf");
        mount.setGroup("workflow");
        MountBodyConfig body = new MountBodyConfig();
        body.setKind("workflow");
        body.setDefinition("intake-v1");
        mount.setBody(body);
        orch.setMounts(List.of(mount));
        def.setOrchestration(orch);

        factory.contribute(def, toolkit);

        assertThat(toolkit.getToolGroup("workflow")).isNotNull();
        // 通道②不依赖 agent 服务；provider 延迟到工具调用时才 provide
        verifyNoInteractions(agentService);
    }

    @Test
    void agentMountCreatesGroupWithoutThrowing() {
        AgentService agentService = mock(AgentService.class);
        OrchestrationTrigger trigger = mock(OrchestrationTrigger.class);
        WorkflowRunStateStore store = new WorkflowRunStateStore.InMemory();

        MountFactory factory = new MountFactory(agentService, trigger, store);
        Toolkit toolkit = new Toolkit();

        AgentDefinition def = new AgentDefinition();
        def.setName("host");
        OrchestrationConfig orch = new OrchestrationConfig();
        orch.setPattern("single");
        MountConfig mount = new MountConfig();
        mount.setName("sub");
        MountBodyConfig body = new MountBodyConfig();
        body.setKind("agent");
        body.setRef("text2sql-agent");
        mount.setBody(body);
        orch.setMounts(List.of(mount));
        def.setOrchestration(orch);

        factory.contribute(def, toolkit);

        assertThat(toolkit.getToolGroup("agent")).isNotNull();
    }
}
