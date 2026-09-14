package io.yunxi.platform.aistio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * 会话运行状态（{@code GET /agentscope/sessions/{id}/state}）。
 */
@Data
public class SessionState {

    /** 运行状态：runnable / running / idle */
    private String status;

    /** 最后活跃时间（ISO-8601） */
    @JsonProperty("last_activity")
    private String lastActivity;

    /** 消息数 */
    @JsonProperty("message_count")
    private int messageCount;

    /** plan-mode 是否激活（Phase 3 plan-mode 能力） */
    @JsonProperty("plan_mode_active")
    private boolean planModeActive;

    /** 所属智能体 */
    private String agent;
}
