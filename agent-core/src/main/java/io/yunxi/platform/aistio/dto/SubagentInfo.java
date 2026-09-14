package io.yunxi.platform.aistio.dto;

import java.util.List;

import lombok.Data;

/**
 * 子智能体 / 编排节点（{@code GET /agentscope/subagents}，能力 subagent-inventory）。
 *
 * <p>递归结构：supervisor / routing 模式下 {@code children} 为专家列表，
 * 叶节点（single / 专家本身）{@code children} 为空。</p>
 */
@Data
public class SubagentInfo {

    /** 智能体名称 */
    private String name;

    /** 编排模式：single / supervisor / pipeline / routing */
    private String pattern;

    /** 描述 */
    private String description;

    /** 子节点（专家 / 下一阶段） */
    private List<SubagentInfo> children;

    /** 该节点声明的工具名集合 */
    private List<String> tools;
}
