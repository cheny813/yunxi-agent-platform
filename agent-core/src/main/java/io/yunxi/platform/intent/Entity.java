package io.yunxi.platform.intent;

/**
 * NER 识别出的实体。
 *
 * <p>框架通用值对象：实体类型由业务方在 NER 词典（ner-dictionaries.yml）中定义，
 * 本 record 仅承载识别结果。内置词典的示例类型如 MEAL/CROWD/NUTRIENT/DATE 等，
 * 业务方可按需扩展任意类型。</p>
 *
 * @param type       实体类型（如 DATE/CROWD/QUANTITY 等，由词典定义）
 * @param value      原文
 * @param normalized 归一化值（value 的 trim+全角转半角+小写；不做语义归一）
 * @param start      起始字符偏移（含）
 * @param end        结束字符偏移（不含）
 * @param source     来源：DICT（词典）| REGEX（正则）
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public record Entity(
        String type,
        String value,
        String normalized,
        int start,
        int end,
        String source) {
}
