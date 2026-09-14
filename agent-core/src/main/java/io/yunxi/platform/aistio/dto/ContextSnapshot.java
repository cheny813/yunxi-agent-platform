package io.yunxi.platform.aistio.dto;

import lombok.Data;

/**
 * 有效上下文窗口（{@code GET /agentscope/sessions/{id}/context}，能力 context-query）。
 *
 * <p>当前映射到「最近 N 轮有效消息窗口」的拼接文本；待接入 GA {@code CompactionMiddleware}
 * 后可替换为真正的压缩上下文。</p>
 */
@Data
public class ContextSnapshot {

    /** 拼接后的有效上下文文本 */
    private String context;

    /** 近似 token 数 */
    private int tokens;
}
