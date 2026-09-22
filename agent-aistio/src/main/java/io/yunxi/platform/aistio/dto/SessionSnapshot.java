package io.yunxi.platform.aistio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * 会话快照（{@code GET /agentscope/sessions} 数组元素）。
 */
@Data
public class SessionSnapshot {

    /** 会话 ID */
    private String id;

    /** 智能体名称 */
    private String agentName;

    /** 用户 ID（workspace 命名空间） */
    @JsonProperty("user_id")
    private String userId;

    /** 创建时间（ISO-8601） */
    @JsonProperty("created_at")
    private String createdAt;

    /** 最后活跃时间（ISO-8601） */
    @JsonProperty("updated_at")
    private String updatedAt;

    /** 会话状态：runnable / running / idle（详见 SessionState） */
    private String status;
}
