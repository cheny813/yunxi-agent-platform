package io.yunxi.config;

import java.util.Map;

/**
 * 配置平面。一个具名、带类型的配置片段。配置片段是配置中心存储的唯一单元；其他平面不得直接绑定片段（唯一真相源）。
 *
 * @param id      稳定的片段标识（被其它位置的 {@code *Ref} 字段引用）
 * @param kind    片段类型（如 {@code model}、{@code state}、{@code routing}、{@code observability}）
 * @param content 不透明的片段内容，由持有该类型的消费方按需惰性解析
 */
public record ConfigFragment(String id, String kind, Map<String, Object> content) {
}
