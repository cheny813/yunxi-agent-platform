package io.yunxi.intelligence;

/**
 * 智能平面。意图引擎契约：从带上下文的请求推断目标 Agent。
 *
 * <p>引擎返回结构化 {@link IntentResolution}（含实体与改写）；旧的
 * {@link #resolve(String)} 保留为兼容投影，供尚未迁移的调用方使用。</p>
 */
public interface IntentEngine {

    /**
     * 解析意图。任何实现都必须 fail-open：解析失败不得抛出异常，而是返回默认兜底结果。
     *
     * @param request 带租户/会话上下文的请求
     * @return 结构化解析结果（永不抛异常）
     */
    IntentResolution resolve(IntentRequest request);

    /**
     * 兼容投影：仅按文本解析（无租户过滤）。新代码请使用 {@link #resolve(IntentRequest)}。
     */
    default Intent resolve(String text) {
        return resolve(new IntentRequest(text, null, null, 0)).asIntent();
    }
}
