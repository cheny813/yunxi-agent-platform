package io.yunxi.platform.agent.workspace;

import io.yunxi.platform.agent.workspace.model.SceneDetectionRule;
import io.yunxi.platform.agent.workspace.WorkspaceAutoDiscoveryEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 默认场景检测器
 *
 * <p>基于工作区 AGENTS.md 中解析的场景检测规则，通过关键词匹配检测用户消息所属场景。</p>
 *
 * <p>检测优先级顺序：</p>
 * <ol>
 *   <li>工作区 AGENTS.md 规则（由 {@link WorkspaceAutoDiscoveryEngine} 发现）</li>
 *   <li>全局场景规则（跨 Agent）</li>
 *   <li>内置关键词（兜底）</li>
 * </ol>
 *
 * @author yunxi-agent-platform
 */
public class DefaultSceneDetector {

    private static final Logger log = LoggerFactory.getLogger(DefaultSceneDetector.class);

    /** 工作区自动发现引擎 */
    private final WorkspaceAutoDiscoveryEngine discoveryEngine;

    /** 内置兜底关键词 */
    private static final List<String> FALLBACK_KEYWORDS = List.of(
            "记得", "我喜欢", "我不喜欢", "我的习惯", "我的偏好",
            "我的工作", "我的家人", "我的生活", "回忆",
            "职业", "擅长", "专业", "技能");

    private static final String GENERAL_SCENE = "GENERAL";
    private static final String PERSONAL_ASSISTANT_SCENE = "PERSONAL_ASSISTANT";

    public DefaultSceneDetector(WorkspaceAutoDiscoveryEngine discoveryEngine) {
        this.discoveryEngine = discoveryEngine;
    }

    /**
     * 检测用户消息所属场景
     *
     * @param agentName Agent 名称
     * @param userMessage 用户消息文本
     * @return 场景名称
     */
    public String detectScene(String agentName, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return GENERAL_SCENE;

        String workspaceScene = detectByWorkspaceRules(agentName, userMessage);
        if (workspaceScene != null) return workspaceScene;

        String globalScene = detectByGlobalRules(userMessage);
        if (globalScene != null) return globalScene;

        return detectByFallbackKeywords(userMessage);
    }

    private String detectByWorkspaceRules(String agentName, String text) {
        return discoveryEngine.getWorkspaceConfig(agentName)
                .map(config -> {
                    SceneDetectionRule rule = config.getSceneRule();
                    if (rule != null && rule.matches(text)) {
                        log.debug("通过工作区规则检测到场景: agent={}, scene={}", agentName, rule.getSceneName());
                        return rule.getSceneName();
                    }
                    return null;
                })
                .orElse(null);
    }

    private String detectByGlobalRules(String text) {
        String lowerText = text.toLowerCase();
        return discoveryEngine.getDiscoveredSceneRules().values().stream()
                .sorted((a, b) -> Integer.compare(a.getPriority(), b.getPriority()))
                .filter(rule -> rule.matches(lowerText))
                .findFirst()
                .map(rule -> {
                    log.debug("通过全局规则检测到场景: scene={}", rule.getSceneName());
                    return rule.getSceneName();
                })
                .orElse(null);
    }

    private String detectByFallbackKeywords(String text) {
        String lowerText = text.toLowerCase();
        for (String keyword : FALLBACK_KEYWORDS) {
            if (lowerText.contains(keyword)) {
                log.debug("通过内置关键词检测到场景: {}", PERSONAL_ASSISTANT_SCENE);
                return PERSONAL_ASSISTANT_SCENE;
            }
        }
        return GENERAL_SCENE;
    }
}
