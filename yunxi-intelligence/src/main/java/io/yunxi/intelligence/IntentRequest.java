package io.yunxi.intelligence;

/**
 * 智能平面。意图解析请求上下文。除了本轮文本，还携带候选收窄所需的租户维度与可调的候选上限，
 * 使分类器只面对「该租户可用且数量可控」的候选闭集。
 *
 * @param text          本轮用户文本（原始文本，引擎内部负责归一化）
 * @param tenantId      租户 id；为空表示未启用多租户（候选不按租户过滤）
 * @param sessionId     会话 id（预留多轮上下文；v1 不消费）
 * @param maxCandidates 候选上限；0 表示用引擎配置的默认值
 */
public record IntentRequest(String text, String tenantId, String sessionId, int maxCandidates) {

    /** 便捷构造：带租户的常规请求，候选上限交由引擎默认。 */
    public static IntentRequest of(String text, String tenantId) {
        return new IntentRequest(text, tenantId, null, 0);
    }
}
