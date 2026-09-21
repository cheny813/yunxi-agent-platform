package io.yunxi.platform.shared.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 编排模式准入门禁回归测试。
 *
 * <p>背景：pipeline / routing / handoffs 曾经"配得上、跑不起来" —— 加载期校验通过、
 * 但 {@code AgentConfigurer} 只打日志不注册实例，Agent 列表里有名字、调用时 not found。
 * 门禁规则是：接线一个、放开一个，未接线模式一律在加载期拒绝。
 */
class AgentDefinitionLoaderPatternTest {

    private final AgentDefinitionLoader loader = new AgentDefinitionLoader();

    @Test
    void singleIsAllowed() {
        assertTrue(errorsOf(defWithPattern("single")).isEmpty());
    }

    @Test
    void nullPatternFallsBackToSingle() {
        AgentDefinition def = new AgentDefinition();
        def.setName("t");
        def.setPrompt("p");
        OrchestrationConfig orch = new OrchestrationConfig();
        orch.setPattern(null);
        def.setOrchestration(orch);
        assertTrue(loader.validateDefinition(def, "t.yml").isEmpty());
    }

    @Test
    void supervisorIsAllowedWithExperts() {
        AgentDefinition def = defWithPattern("supervisor");
        ExpertConfig expert = new ExpertConfig();
        expert.setName("expert-a");
        def.getOrchestration().setExperts(List.of(expert));
        assertTrue(loader.validateDefinition(def, "t.yml").isEmpty());
    }

    @Test
    void supervisorWithoutExpertsIsRejected() {
        List<String> errors = errorsOf(defWithPattern("supervisor"));
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("experts")));
    }

    @Test
    void pipelineIsRejectedBecauseSchedulingNotWired() {
        AgentDefinition def = defWithPattern("pipeline");
        StageConfig stage = new StageConfig();
        stage.setAgent("some-agent");
        def.getOrchestration().setStages(List.of(stage));

        List<String> errors = errorsOf(def);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("未接线")),
                "pipeline 必须以「调度未接线」为由拒绝，而不是靠 stages 校验放行");
    }

    @Test
    void routingIsRejectedBecauseSchedulingNotWired() {
        AgentDefinition def = defWithPattern("routing");
        ExpertConfig expert = new ExpertConfig();
        expert.setName("expert-a");
        def.getOrchestration().setExperts(List.of(expert));

        assertFalse(errorsOf(def).isEmpty(), "routing 即使配了 experts 也必须拒绝");
    }

    @Test
    void handoffsIsRejected() {
        assertFalse(errorsOf(defWithPattern("handoffs")).isEmpty());
    }

    @Test
    void patternIsCaseInsensitive() {
        assertTrue(errorsOf(defWithPattern("SINGLE")).isEmpty());
        assertFalse(errorsOf(defWithPattern("PIPELINE")).isEmpty());
    }

    @Test
    void workflowIsAllowedOnlyWithWorkflowMount() {
        AgentDefinition def = defWithPattern("workflow");
        def.getOrchestration().setMounts(List.of(workflowMount()));
        assertTrue(loader.validateDefinition(def, "t.yml").isEmpty());
    }

    @Test
    void workflowWithoutMountIsRejected() {
        List<String> errors = errorsOf(defWithPattern("workflow"));
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("workflow")),
                "声明型 workflow 无图可导出，属无效配置，必须拒绝");
    }

    @Test
    void onlyWorkflowIsDeclarationOnly() {
        assertFalse(new OrchestrationConfig().isDeclarationOnly(), "默认 pattern=single 不是声明型");
        OrchestrationConfig orch = new OrchestrationConfig();
        orch.setPattern("WORKFLOW");
        assertTrue(orch.isDeclarationOnly());
    }

    private List<String> errorsOf(AgentDefinition def) {
        return loader.validateDefinition(def, "t.yml");
    }

    private static AgentDefinition defWithPattern(String pattern) {
        AgentDefinition def = new AgentDefinition();
        def.setName("t");
        def.setPrompt("p");
        OrchestrationConfig orch = new OrchestrationConfig();
        orch.setPattern(pattern);
        def.setOrchestration(orch);
        return def;
    }

    private static MountConfig workflowMount() {
        MountBodyConfig body = new MountBodyConfig();
        body.setKind("workflow");
        MountConfig mount = new MountConfig();
        mount.setName("m");
        mount.setBody(body);
        return mount;
    }
}
