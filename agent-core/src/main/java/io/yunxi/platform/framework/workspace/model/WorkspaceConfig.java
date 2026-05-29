package io.yunxi.platform.framework.workspace.model;

import java.util.List;

/**
 * 工作区配置模型
 *
 * <p>描述一个 Agent 工作区的完整配置，包含场景检测规则、知识库、技能和子智能体等信息。
 * 由 {@link io.yunxi.platform.framework.workspace.WorkspaceAutoDiscoveryEngine} 在启动时自动扫描生成。</p>
 *
 * @author yunxi-agent-platform
 */
public class WorkspaceConfig {

    /** 工作区路径 */
    private final String workspacePath;

    /** Agent 名称 */
    private final String agentName;

    /** 场景检测规则（从 AGENTS.md 解析） */
    private SceneDetectionRule sceneRule;

    /** knowledge/ 目录下的知识文件列表 */
    private List<String> knowledgeFiles;

    /** skills/ 目录下的技能列表 */
    private List<String> skillNames;

    /** subagents/ 目录下的子智能体列表 */
    private List<String> subAgentIds;

    /** 工作区是否完整有效 */
    private boolean valid;

    /** 校验消息 */
    private String validationMessage;

    public WorkspaceConfig(String workspacePath, String agentName) {
        this.workspacePath = workspacePath;
        this.agentName = agentName;
    }

    public String getWorkspacePath() {
        return workspacePath;
    }

    public String getAgentName() {
        return agentName;
    }

    public SceneDetectionRule getSceneRule() {
        return sceneRule;
    }

    public void setSceneRule(SceneDetectionRule sceneRule) {
        this.sceneRule = sceneRule;
    }

    public List<String> getKnowledgeFiles() {
        return knowledgeFiles;
    }

    public void setKnowledgeFiles(List<String> knowledgeFiles) {
        this.knowledgeFiles = knowledgeFiles;
    }

    public List<String> getSkillNames() {
        return skillNames;
    }

    public void setSkillNames(List<String> skillNames) {
        this.skillNames = skillNames;
    }

    public List<String> getSubAgentIds() {
        return subAgentIds;
    }

    public void setSubAgentIds(List<String> subAgentIds) {
        this.subAgentIds = subAgentIds;
    }

    public boolean isValid() {
        return valid;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }

    public String getValidationMessage() {
        return validationMessage;
    }

    public void setValidationMessage(String validationMessage) {
        this.validationMessage = validationMessage;
    }
}