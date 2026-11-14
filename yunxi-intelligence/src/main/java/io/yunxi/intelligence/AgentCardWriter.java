package io.yunxi.intelligence;

import io.yunxi.definition.AgentSpec;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能平面。分类卡片派生：优先使用作者在 {@code AgentSpec.intent.hint} 显式声明的短卡片；
 * 未声明时从 {@code identity} 派生「名称 + 首句简介」。{@code identity.description} 同时是系统提示词，
 * 其全文绝不进入卡片——既控制 token 成本，也避免把指令性文本暴露给分类模型（缩小提示注入面）。
 */
public final class AgentCardWriter {

    private static final int MAX_CARD_CHARS = 120;

    private AgentCardWriter() {
    }

    /** 派生分类卡片：显式 hint 优先，description 缺失时仅返回名称。 */
    public static String derive(AgentSpec spec) {
        if (spec.intent() != null && spec.intent().hint() != null && !spec.intent().hint().isBlank()) {
            return spec.intent().hint().strip();
        }
        String name = spec.identity().name();
        if (name == null || name.isBlank()) {
            name = spec.identity().agentId();
        }
        String firstSentence = firstSentence(spec.identity().description());
        if (firstSentence.isBlank()) {
            return name;
        }
        return name + "：" + firstSentence;
    }

    /** 取第一句（含句末标点），剥离角色指令前缀，截断到上限。 */
    private static String firstSentence(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }
        String sentence = description.strip();
        Matcher m = Pattern.compile("^.*?[。！？!?；;\\n]").matcher(sentence);
        if (m.find()) {
            sentence = m.group();
        }
        sentence = stripRolePrefix(sentence.strip());
        if (sentence.length() > MAX_CARD_CHARS) {
            sentence = sentence.substring(0, MAX_CARD_CHARS) + "…";
        }
        return sentence;
    }

    private static String stripRolePrefix(String sentence) {
        return sentence
                .replaceFirst("^你是一个?", "")
                .replaceFirst("^你是一名?", "")
                .replaceFirst("^你是", "")
                .replaceFirst("^You are( an?)?", "");
    }
}
