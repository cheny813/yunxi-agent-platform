package io.yunxi.platform.aistio.orchestration;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.yunxi.platform.shared.config.ApprovalConfig;
import io.yunxi.platform.shared.config.GraphNodeConfig;
import io.yunxi.platform.shared.config.JoinConfig;
import io.yunxi.platform.shared.config.RetryConfig;
import io.yunxi.platform.shared.config.TimerConfig;

/**
 * 编排图节点（aistio DefinitionSpec.nodes 元素）。
 * <p>由 {@link GraphNodeConfig} 映射而来，仅保留非空字段（@JsonInclude NON_NULL），
 * 因此导出的 artifact 不含类型无关的空字段。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DefinitionNode {

    private String key;
    private String type;
    private String agentId;
    private String teamRef;
    private JoinConfig join;
    private String condition;
    private ApprovalConfig approval;
    private TimerConfig timer;
    private String signalName;
    private String failurePolicy;
    private String issueMode;
    private Integer timeoutSeconds;
    private RetryConfig retry;
    private String definitionRevisionId;

    public static DefinitionNode from(GraphNodeConfig src) {
        DefinitionNode n = new DefinitionNode();
        n.key = src.getKey();
        n.type = src.getType();
        n.agentId = src.getAgentId();
        n.teamRef = src.getTeamRef();
        n.join = src.getJoin();
        n.condition = src.getCondition();
        n.approval = src.getApproval();
        n.timer = src.getTimer();
        n.signalName = src.getSignalName();
        n.failurePolicy = src.getFailurePolicy();
        n.issueMode = src.getIssueMode();
        n.timeoutSeconds = src.getTimeoutSeconds();
        n.retry = src.getRetry();
        n.definitionRevisionId = src.getDefinitionRevisionId();
        return n;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getTeamRef() {
        return teamRef;
    }

    public void setTeamRef(String teamRef) {
        this.teamRef = teamRef;
    }

    public JoinConfig getJoin() {
        return join;
    }

    public void setJoin(JoinConfig join) {
        this.join = join;
    }

    public String getCondition() {
        return condition;
    }

    public void setCondition(String condition) {
        this.condition = condition;
    }

    public ApprovalConfig getApproval() {
        return approval;
    }

    public void setApproval(ApprovalConfig approval) {
        this.approval = approval;
    }

    public TimerConfig getTimer() {
        return timer;
    }

    public void setTimer(TimerConfig timer) {
        this.timer = timer;
    }

    public String getSignalName() {
        return signalName;
    }

    public void setSignalName(String signalName) {
        this.signalName = signalName;
    }

    public String getFailurePolicy() {
        return failurePolicy;
    }

    public void setFailurePolicy(String failurePolicy) {
        this.failurePolicy = failurePolicy;
    }

    public String getIssueMode() {
        return issueMode;
    }

    public void setIssueMode(String issueMode) {
        this.issueMode = issueMode;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public RetryConfig getRetry() {
        return retry;
    }

    public void setRetry(RetryConfig retry) {
        this.retry = retry;
    }

    public String getDefinitionRevisionId() {
        return definitionRevisionId;
    }

    public void setDefinitionRevisionId(String definitionRevisionId) {
        this.definitionRevisionId = definitionRevisionId;
    }
}
