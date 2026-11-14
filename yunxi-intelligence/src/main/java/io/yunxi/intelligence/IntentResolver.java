package io.yunxi.intelligence;

/**
 * 智能平面。意图解析器 SPI（引擎管线中的一环）。实现返回单条结果；未命中返回 {@code null}，
 * 由引擎决定回落路径。
 */
public interface IntentResolver {

    /**
     * 解析一次意图；未命中返回 {@code null}。
     */
    IntentResolution resolve(IntentRequest request);
}
