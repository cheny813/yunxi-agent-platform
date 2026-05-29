package io.yunxi.platform.framework.workspace.model;

import java.util.Collections;
import java.util.List;

/**
 * 场景检测规则
 *
 * <p>从工作区 AGENTS.md 的 {@code # 场景检测} 段落解析而来，
 * 用于替代传统的 {@link io.yunxi.platform.framework.spi.SceneContributor} SPI。
 * 框架通过关键词匹配自动检测用户消息所属场景。</p>
 *
 * @author yunxi-agent-platform
 */
public class SceneDetectionRule {

    /** 场景名称 */
    private final String sceneName;

    /** 触发关键词列表 */
    private final List<String> keywords;

    /** 场景上下文描述（注入 system prompt） */
    private final String contextDescription;

    /** 场景检测优先级（数字越小优先级越高，默认 100） */
    private int priority = 100;

    public SceneDetectionRule(String sceneName, List<String> keywords, String contextDescription) {
        this.sceneName = sceneName;
        this.keywords = keywords != null ? keywords : Collections.emptyList();
        this.contextDescription = contextDescription;
    }

    public SceneDetectionRule(String sceneName, List<String> keywords, String contextDescription, int priority) {
        this(sceneName, keywords, contextDescription);
        this.priority = priority;
    }

    /**
     * 检测文本是否匹配此场景
     */
    public boolean matches(String text) {
        if (text == null || keywords.isEmpty()) {
            return false;
        }
        String lowerText = text.toLowerCase();
        return keywords.stream().anyMatch(kw -> lowerText.contains(kw.toLowerCase()));
    }

    public String getSceneName() {
        return sceneName;
    }

    public List<String> getKeywords() {
        return keywords;
    }

    public String getContextDescription() {
        return contextDescription;
    }

    public int getPriority() {
        return priority;
    }
}