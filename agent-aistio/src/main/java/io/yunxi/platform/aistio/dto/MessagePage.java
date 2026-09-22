package io.yunxi.platform.aistio.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * 完整消息分页（{@code GET /agentscope/sessions/{id}/messages}，能力 message-query）。
 */
@Data
public class MessagePage {

    /** 本页消息，元素为 {role, content, thinking} 映射 */
    private List<Object> messages;

    /** 消息总数 */
    private int total;

    /** 是否还有更多 */
    @JsonProperty("has_more")
    private boolean hasMore;
}
