package io.yunxi.platform.integration.contract.dto;

import lombok.Data;

/**
 * 有效上下文窗口（{@code GET /agentscope/sessions/{id}/context}，能力 context-query）。
 *
 * <p>优先返回框架 {@code ConversationCompactor} 压缩结果（与主体 Agent 一致的运行时压缩内核）；
 * 未超窗口或压缩不可用时退化为「最近 N 轮有效消息窗口」的拼接文本。</p>
 */
@Data
public class ContextSnapshot {

    /** 拼接后的有效上下文文本 */
    private String context;

    /** 近似 token 数 */
    private int tokens;
}
