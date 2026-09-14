package io.yunxi.platform.aistio.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 任务信息（{@code GET /agentscope/sessions/{id}/tasks}、{@code /subagent-tasks}）。
 *
 * <p>数据来源于 GA {@code AgentState.tasksContext}（由 {@code enableTaskList(true)} 注册
 * 的 {@code todo_write} 工具持久化）。</p>
 */
@Data
public class TaskInfo {

    /** 任务 ID */
    private String id;

    /** 任务主题 */
    private String subject;

    /** 任务描述 */
    private String description;

    /** 任务状态：pending / in_progress / completed */
    private String state;

    /** 归属（执行该任务的子智能体） */
    private String owner;

    /** 创建时间（ISO-8601） */
    private String createdAt;

    /** 上游依赖任务 ID 列表 */
    @JsonProperty("blocked_by")
    private List<String> blockedBy;
}
