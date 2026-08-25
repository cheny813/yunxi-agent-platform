package io.yunxi.platform.intent;

/**
 * 意图引擎统一入口（四阶段前置管道：NER → 改写 → 分类 → 映射）。
 *
 * <p>M1 规则通道实现见 {@link DefaultIntentEngine}。所有实现必须保证：
 * 任何内部异常不向外抛出（降级安全），见设计文档 1.2 节原则 4。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public interface IntentEngine {
    /**
     * 分析用户消息，产出结构化意图结果。
     *
     * @param context 分析上下文（不可为 null）
     * @return 永不返回 null；降级时返回阶段性结果（degraded=true）
     */
    IntentResult analyze(IntentContext context);
}
