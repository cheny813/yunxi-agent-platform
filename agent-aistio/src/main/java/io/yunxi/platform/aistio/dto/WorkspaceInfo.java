package io.yunxi.platform.aistio.dto;

import java.util.List;

import lombok.Data;

/**
 * 工作空间（{@code GET /agentscope/workspaces}，能力 workspace-inventory）。
 *
 * <p>在 yunxi 中 workspace 即按 userId 隔离的命名空间（与 AgentScope-Java HarnessAgent.workspaceFor 一致）。</p>
 */
@Data
public class WorkspaceInfo {

    /** workspace ID（userId） */
    private String id;

    /** 类型，固定为 user */
    private String type = "user";

    /** 该 workspace 下使用过的智能体名称集合 */
    private List<String> agents;
}
