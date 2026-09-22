package io.yunxi.platform.aistio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.yunxi.platform.shared.codec.CodecSpec;

import io.yunxi.platform.shared.config.GraphConfig;
import io.yunxi.platform.shared.config.GraphEdgeConfig;
import io.yunxi.platform.shared.config.GraphNodeConfig;
import io.yunxi.platform.shared.config.JoinConfig;
import io.yunxi.platform.shared.config.TimerConfig;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 编排图翻译器：将 YAML 声明的 {@link GraphConfig} 校验并转为 aistio DefinitionSpec artifact。
 * <p>预校验分级（HARD 硬阻断 / WARN 软告警）：
 * <ul>
 *   <li>HARD —— 违反则 {@link #translate} 抛 {@link OrchestrationSpecValidationException}，不导出；</li>
 *   <li>WARN —— 仍导出，结论写入 manifest；</li>
 * </ul>
 * 校验口径对齐 aistio 服务端 DefinitionSpec 规则；条件节点的 CEL 表达式经 CEL 引擎做语法解析，
 * 语义级校验交由服务端导入期兜底。</p>
 */
public class OrchestrationTranslator {

    public static final List<String> NODE_TYPES =
            List.of("agent", "team", "join", "condition", "approval", "timer", "signal", "subrun");

    public static final List<String> TERMINAL_STATES =
            List.of("success", "failure", "timeout", "rejected", "canceled", "skipped", "error");

    public static final List<String> JOIN_MODES = List.of("all", "any", "quorum");

    public static final List<String> FAILURE_POLICIES = List.of("fail_fast", "continue", "partial_success");

    public static final List<String> ISSUE_MODES = List.of("inherit", "child");

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final ObjectMapper objectMapper;
    private final int maxNodes;
    private final int maxEdges;

    public OrchestrationTranslator() {
        this(CodecSpec.storage(), 200, 500);
    }

    public OrchestrationTranslator(ObjectMapper objectMapper, int maxNodes, int maxEdges) {
        this.objectMapper = objectMapper;
        this.maxNodes = maxNodes;
        this.maxEdges = maxEdges;
    }

    /**
     * 预校验（不抛异常，返回完整结果）。
     *
     * @param graph    编排图
     * @param registry 已注册实体目录（E3 校验），可为空集合
     * @return 校验结果
     */
    public ValidationResult validate(GraphConfig graph, EntityRegistry registry) {
        ValidationResult result = new ValidationResult();
        if (graph == null || graph.getNodes() == null || graph.getNodes().isEmpty()) {
            result.add(new ValidationIssue(ValidationSeverity.HARD, "GRAPH_EMPTY", null, "编排图为空或没有节点"));
            return result;
        }

        Map<String, GraphNodeConfig> nodeByKey = new HashMap<>();
        Set<String> keys = new HashSet<>();
        for (GraphNodeConfig node : graph.getNodes()) {
            if (node.getKey() == null || node.getKey().isBlank()) {
                result.add(new ValidationIssue(ValidationSeverity.HARD, "NODE_KEY_EMPTY", null, "存在 key 为空的节点"));
            } else if (!keys.add(node.getKey())) {
                result.add(new ValidationIssue(ValidationSeverity.HARD, "NODE_KEY_DUP", node.getKey(),
                        "节点 key 重复: " + node.getKey()));
            } else {
                nodeByKey.put(node.getKey(), node);
            }
        }

        Map<String, Integer> indegree = computeIndegree(graph);
        for (GraphNodeConfig node : graph.getNodes()) {
            validateNode(node, indegree, result, registry);
        }
        for (GraphEdgeConfig edge : graph.getEdges()) {
            validateEdge(edge, nodeByKey, result);
        }

        if (!hasHard(result)) {
            checkDag(graph, nodeByKey, result);
        }

        if (graph.getNodes().size() > maxNodes || graph.getEdges().size() > maxEdges) {
            result.add(new ValidationIssue(ValidationSeverity.WARN, "GRAPH_SIZE", null,
                    String.format("图规模超出建议上限（节点 %d>%d 或边 %d>%d），仍导出，请在导入后评估",
                            graph.getNodes().size(), maxNodes, graph.getEdges().size(), maxEdges)));
        }
        return result;
    }

    private void validateNode(GraphNodeConfig node, Map<String, Integer> indegree,
            ValidationResult result, EntityRegistry registry) {
        String key = node.getKey();
        if (node.getType() == null || !NODE_TYPES.contains(node.getType())) {
            result.add(new ValidationIssue(ValidationSeverity.HARD, "NODE_TYPE_INVALID", key,
                    "节点类型非法: " + node.getType() + "（可选 " + NODE_TYPES + "）"));
            return;
        }
        switch (node.getType()) {
            case "agent" -> {
                if (!isUuid(node.getAgentId())) {
                    result.add(new ValidationIssue(ValidationSeverity.HARD, "NODE_AGENT_ID", key,
                            "agent 节点 agentId 非法 UUID: " + node.getAgentId()));
                } else {
                    checkRegistered(node.getAgentId(), "agent", key, registry, result);
                }
            }
            case "team" -> {
                if (!isUuid(node.getTeamRef())) {
                    result.add(new ValidationIssue(ValidationSeverity.HARD, "NODE_TEAM_REF", key,
                            "team 节点 teamRef 非法 UUID: " + node.getTeamRef()));
                } else {
                    checkRegistered(node.getTeamRef(), "team", key, registry, result);
                }
            }
            case "join" -> validateJoin(node, key, indegree, result);
            case "condition" -> {
                if (node.getCondition() == null || !CelValidator.isCompilable(node.getCondition())) {
                    result.add(new ValidationIssue(ValidationSeverity.HARD, "CEL_NOT_COMPILABLE", key,
                            "condition 节点 CEL 表达式不可编译（长度≤4096 且语法正确）: " + node.getCondition()));
                }
            }
            case "approval" -> {
                if (node.getApproval() == null || isBlank(node.getApproval().getApproverRef())) {
                    result.add(new ValidationIssue(ValidationSeverity.HARD, "APPROVAL_REF", key,
                            "approval 节点缺少 approverRef"));
                }
            }
            case "timer" -> {
                TimerConfig t = node.getTimer();
                boolean ok = t != null && (!isBlank(t.getAt()) || (t.getDurationSeconds() != null && t.getDurationSeconds() > 0));
                if (!ok) {
                    result.add(new ValidationIssue(ValidationSeverity.HARD, "TIMER_REQ", key,
                            "timer 节点必须提供 at 或正 durationSeconds"));
                }
            }
            case "signal" -> {
                if (isBlank(node.getSignalName())) {
                    result.add(new ValidationIssue(ValidationSeverity.HARD, "SIGNAL_NAME", key,
                            "signal 节点缺少 signalName"));
                }
            }
            case "subrun" -> {
                if (!isUuid(node.getDefinitionRevisionId())) {
                    result.add(new ValidationIssue(ValidationSeverity.HARD, "SUBRUN_REVISION", key,
                            "subrun 节点 definitionRevisionId 非法 UUID: " + node.getDefinitionRevisionId()));
                } else {
                    checkRegistered(node.getDefinitionRevisionId(), "subrun", key, registry, result);
                }
            }
            default -> { }
        }
        if (!isBlank(node.getFailurePolicy()) && !FAILURE_POLICIES.contains(node.getFailurePolicy())) {
            result.add(new ValidationIssue(ValidationSeverity.HARD, "FAILURE_POLICY", key,
                    "failurePolicy 非法: " + node.getFailurePolicy()));
        }
        if (!isBlank(node.getIssueMode()) && !ISSUE_MODES.contains(node.getIssueMode())) {
            result.add(new ValidationIssue(ValidationSeverity.HARD, "ISSUE_MODE", key,
                    "issueMode 非法: " + node.getIssueMode()));
        }
    }

    private void validateJoin(GraphNodeConfig node, String key, Map<String, Integer> indegree,
            ValidationResult result) {
        JoinConfig join = node.getJoin();
        if (join == null || !JOIN_MODES.contains(join.getMode())) {
            result.add(new ValidationIssue(ValidationSeverity.HARD, "JOIN_MODE", key,
                    "join 节点 join.mode 非法: " + (join == null ? null : join.getMode())));
            return;
        }
        if ("quorum".equals(join.getMode())) {
            int in = indegree.getOrDefault(key, 0);
            if (join.getQuorum() == null || join.getQuorum() <= 0 || join.getQuorum() > in) {
                result.add(new ValidationIssue(ValidationSeverity.HARD, "JOIN_QUORUM", key,
                        "join.quorum 须 >0 且 ≤ 入度(" + in + "): " + join.getQuorum()));
            }
        }
    }

    private void validateEdge(GraphEdgeConfig edge, Map<String, GraphNodeConfig> nodeByKey,
            ValidationResult result) {
        if (!nodeByKey.containsKey(edge.getFrom())) {
            result.add(new ValidationIssue(ValidationSeverity.HARD, "EDGE_REF", edge.getFrom(),
                    "边起点不存在: " + edge.getFrom()));
        }
        if (!nodeByKey.containsKey(edge.getTo())) {
            result.add(new ValidationIssue(ValidationSeverity.HARD, "EDGE_REF", edge.getTo(),
                    "边终点不存在: " + edge.getTo()));
        }
        if (edge.getFrom() != null && edge.getFrom().equals(edge.getTo())) {
            result.add(new ValidationIssue(ValidationSeverity.HARD, "EDGE_SELF_LOOP", edge.getFrom(),
                    "存在自环边: " + edge.getFrom()));
        }
        if (edge.getOn() != null) {
            for (String state : edge.getOn()) {
                if (!TERMINAL_STATES.contains(state)) {
                    result.add(new ValidationIssue(ValidationSeverity.HARD, "EDGE_ON_TERMINAL", edge.getFrom(),
                            "边 on 仅允许终态(" + TERMINAL_STATES + "): " + state));
                }
            }
        }
        if (!isBlank(edge.getCondition()) && !CelValidator.isCompilable(edge.getCondition())) {
            result.add(new ValidationIssue(ValidationSeverity.HARD, "CEL_NOT_COMPILABLE", edge.getFrom(),
                    "边 condition CEL 表达式不可编译: " + edge.getCondition()));
        }
    }

    private void checkDag(GraphConfig graph, Map<String, GraphNodeConfig> nodeByKey, ValidationResult result) {
        Map<String, Integer> indegree = computeIndegree(graph);
        Set<String> visited = new HashSet<>();
        Set<String> inProgress = new HashSet<>();
        for (String key : nodeByKey.keySet()) {
            if (hasCycle(key, nodeByKey, graph, visited, inProgress, result)) {
                return;
            }
        }
    }

    private boolean hasCycle(String key, Map<String, GraphNodeConfig> nodeByKey, GraphConfig graph,
            Set<String> visited, Set<String> inProgress, ValidationResult result) {
        if (visited.contains(key)) {
            return false;
        }
        if (inProgress.contains(key)) {
            result.add(new ValidationIssue(ValidationSeverity.HARD, "DAG_CYCLE", key, "编排图存在环，无法拓扑排序"));
            return true;
        }
        inProgress.add(key);
        for (GraphEdgeConfig edge : graph.getEdges()) {
            if (key.equals(edge.getFrom()) && nodeByKey.containsKey(edge.getTo())) {
                if (hasCycle(edge.getTo(), nodeByKey, graph, visited, inProgress, result)) {
                    return true;
                }
            }
        }
        inProgress.remove(key);
        visited.add(key);
        return false;
    }

    private void checkRegistered(String entityId, String kind, String nodeKey, EntityRegistry registry,
            ValidationResult result) {
        if (registry == null || registry.registeredIds().isEmpty()) {
            return;
        }
        if (!registry.isRegistered(entityId)) {
            result.add(new ValidationIssue(ValidationSeverity.HARD, "ENTITY_NOT_REGISTERED", nodeKey,
                    kind + " 节点引用的实体未在本环境注册: " + entityId));
        }
    }

    private Map<String, Integer> computeIndegree(GraphConfig graph) {
        Map<String, Integer> indegree = new HashMap<>();
        for (GraphNodeConfig n : graph.getNodes()) {
            if (n.getKey() != null) {
                indegree.putIfAbsent(n.getKey(), 0);
            }
        }
        for (GraphEdgeConfig e : graph.getEdges()) {
            if (e.getTo() != null) {
                indegree.merge(e.getTo(), 1, Integer::sum);
            }
        }
        return indegree;
    }

    private boolean hasHard(ValidationResult result) {
        return result.getIssues().stream().anyMatch(i -> i.getSeverity() == ValidationSeverity.HARD);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean isUuid(String s) {
        return s != null && UUID_PATTERN.matcher(s).matches();
    }

    /**
     * 翻译并导出。遇到 HARD 问题直接抛异常；WARN 仍生成 artifact。
     *
     * @param graph           编排图
     * @param definitionKey   aistio 定义 key
     * @param translatorVersion translator 版本
     * @param registry        已注册实体目录（可为空）
     * @return 翻译结果（含 JSON 与 manifest）
     */
    public TranslationResult translate(GraphConfig graph, String definitionKey, String translatorVersion,
            EntityRegistry registry) {
        ValidationResult result = validate(graph, registry);
        if (hasHard(result)) {
            List<ValidationIssue> hard = result.getIssues().stream()
                    .filter(i -> i.getSeverity() == ValidationSeverity.HARD).toList();
            throw new OrchestrationSpecValidationException(
                    "编排图预校验未通过，已阻断 artifact 导出，共 " + hard.size() + " 个硬阻断问题", hard);
        }
        DefinitionSpec spec = toSpec(graph);
        try {
            String json = objectMapper.writeValueAsString(spec);
            String checksum = ChecksumUtil.sha256(json);
            Manifest manifest = buildManifest(definitionKey, translatorVersion, checksum, result, graph);
            return new TranslationResult(spec, json, manifest);
        } catch (Exception e) {
            throw new OrchestrationSpecValidationException("序列化 DefinitionSpec 失败: " + e.getMessage(),
                    List.of(new ValidationIssue(ValidationSeverity.HARD, "SERIALIZE", definitionKey,
                            e.getMessage())));
        }
    }

    private DefinitionSpec toSpec(GraphConfig graph) {
        DefinitionSpec spec = new DefinitionSpec();
        for (GraphNodeConfig n : graph.getNodes()) {
            spec.getNodes().add(DefinitionNode.from(n));
        }
        for (GraphEdgeConfig e : graph.getEdges()) {
            spec.getEdges().add(new DefinitionEdge(e.getFrom(), e.getTo(), e.getOn(), e.getCondition()));
        }
        return spec;
    }

    private Manifest buildManifest(String definitionKey, String translatorVersion, String checksum,
            ValidationResult result, GraphConfig graph) {
        Manifest manifest = new Manifest();
        manifest.setDefinitionKey(definitionKey);
        manifest.setTranslatorVersion(translatorVersion);
        manifest.setGeneratedAt(OffsetDateTime.now().toString());
        manifest.setSpecChecksum(checksum);
        manifest.getPrecheck().setHardErrors(List.of());
        manifest.getPrecheck().setWarnings(new ArrayList<>(result.getWarnings()));

        Set<String> agentIds = new HashSet<>();
        Set<String> teamRefs = new HashSet<>();
        Set<String> revisionIds = new HashSet<>();
        for (GraphNodeConfig n : graph.getNodes()) {
            if (!isBlank(n.getAgentId())) {
                agentIds.add(n.getAgentId());
            }
            if (!isBlank(n.getTeamRef())) {
                teamRefs.add(n.getTeamRef());
            }
            if (!isBlank(n.getDefinitionRevisionId())) {
                revisionIds.add(n.getDefinitionRevisionId());
            }
        }
        manifest.getReferencedEntities().setAgentIds(new ArrayList<>(agentIds));
        manifest.getReferencedEntities().setTeamRefs(new ArrayList<>(teamRefs));
        manifest.getReferencedEntities().setDefinitionRevisionIds(new ArrayList<>(revisionIds));
        return manifest;
    }
}
