package io.yunxi.platform.prompt;

import io.agentscope.core.message.Msg;
import io.yunxi.platform.memory.MemoryScene;
import io.yunxi.platform.memory.MemorySceneRegistry;
import io.yunxi.platform.agent.profile.ConceptRegistry;
import io.yunxi.platform.agent.workspace.WorkspaceAutoDiscoveryEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 场景检测服务
 *
 * <p>
 * 自动检测用户当前对话的场景。检测链优先级：
 * <ol>
 *   <li>工作区 AGENTS.md 场景规则（由 {@link WorkspaceAutoDiscoveryEngine} 自动发现）</li>
 *   <li>MemorySceneRegistry 自定义场景关键词检测</li>
 *   <li>ConceptRegistry 领域概念检测</li>
 *   <li>框架内置关键词检测（兜底）</li>
 * </ol>
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 3.3.0
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "true")
public class SceneDetectionService {

    /** 场景检测开关 */
    @Value("${memory.scene-detection.enabled:true}")
    private boolean enabled;

    /** 场景检测模式 */
    @Value("${framework.scene-detection.mode:workspace-agents}")
    private String sceneDetectionMode;

    /** 记忆场景注册表 */
    @Autowired
    private MemorySceneRegistry memorySceneRegistry;

    /** 统一概念注册表（可选，业务层通过 YAML 配置领域概念） */
    @Autowired
    private ObjectProvider<ConceptRegistry> conceptRegistryProvider;

    /** 工作区自动发现引擎 */
    @Autowired
    private WorkspaceAutoDiscoveryEngine discoveryEngine;

    // 内置关键词（通用常量，可修改调整）
    private static final List<String> PERSONAL_ASSISTANT_KEYWORDS = List.of(
            "记忆", "我记得", "喜好", "我的喜好", "我的习惯", "我的偏好",
            "我的工作", "我的家庭", "我的", "我的经历", "小时候", "回忆",
            "职业", "工作", "经验", "擅长", "专业", "技能");

    /**
     * 检测当前对话场景
     *
     * @param query 用户问题
     * @return 场景检测结果（包含场景名称）
     */
    public SceneDetectionResult detectScene(String query) {
        if (!enabled || query == null || query.isEmpty()) {
            return SceneDetectionResult.general();
        }

        // 1. 工作区 AGENTS.md 场景规则检测
        if ("workspace-agents".equals(sceneDetectionMode) || "hybrid".equals(sceneDetectionMode)) {
            SceneDetectionResult workspaceResult = detectByWorkspaceRules(query);
            if (!workspaceResult.isGeneral()) {
                return workspaceResult;
            }
        }

        // 2. MemorySceneRegistry 自定义场景关键词检测
        SceneDetectionResult registryResult = detectByRegistry(query);
        if (!registryResult.isGeneral()) {
            return registryResult;
        }

        // 3. ConceptRegistry 领域概念检测
        SceneDetectionResult conceptResult = detectByConcepts(query);
        if (!conceptResult.isGeneral()) {
            return conceptResult;
        }

        // 4. 框架内置关键词检测（兜底）
        return detectByBuiltinKeywords(query);
    }

    /**
     * 通过工作区 AGENTS.md 规则检测场景
     */
    private SceneDetectionResult detectByWorkspaceRules(String text) {
        for (var entry : discoveryEngine.getDiscoveredSceneRules().entrySet()) {
            if (entry.getValue().matches(text)) {
                log.debug("通过工作区规则检测到场景: scene={}", entry.getKey());
                return SceneDetectionResult.of(entry.getKey());
            }
        }
        return SceneDetectionResult.general();
    }

    /**
     * 通过 MemorySceneRegistry 检测自定义场景
     */
    private SceneDetectionResult detectByRegistry(String text) {
        String lowerText = text.toLowerCase();
        for (var cs : memorySceneRegistry.getCustomScenes().values()) {
            if (cs.keywords() != null) {
                for (String keyword : cs.keywords()) {
                    if (lowerText.contains(keyword.toLowerCase())) {
                        log.debug("通过 MemorySceneRegistry 检测到场景 {}: {}", cs.name(), text);
                        return SceneDetectionResult.of(cs.name());
                    }
                }
            }
        }
        return SceneDetectionResult.general();
    }

    /**
     * 通过 ConceptRegistry 检测领域场景
     */
    private SceneDetectionResult detectByConcepts(String text) {
        if (conceptRegistryProvider.getIfAvailable() == null) {
            return SceneDetectionResult.general();
        }

        Map<String, Double> domains = conceptRegistryProvider.getIfAvailable().detectDomains(text);
        if (domains.isEmpty()) {
            return SceneDetectionResult.general();
        }

        var best = domains.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElse(null);

        if (best != null && best.getValue() > 0) {
            String domain = best.getKey();
            log.debug("通过 ConceptRegistry 检测到领域 {}: score={}, text={}", domain, best.getValue(), text);
            return SceneDetectionResult.of(domain);
        }

        return SceneDetectionResult.general();
    }

    /**
     * 通过框架内置关键词检测场景（兜底逻辑）
     */
    private SceneDetectionResult detectByBuiltinKeywords(String text) {
        String lowerText = text.toLowerCase();
        for (String keyword : PERSONAL_ASSISTANT_KEYWORDS) {
            if (lowerText.contains(keyword)) {
                log.debug("检测到个人助手场景: {}", text);
                return SceneDetectionResult.of(MemoryScene.PERSONAL_ASSISTANT);
            }
        }
        return SceneDetectionResult.general();
    }

    /**
     * 批量检测场景（用于多轮对话）
     */
    public SceneDetectionResult detectSceneFromMessages(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return SceneDetectionResult.general();
        }

        StringBuilder fullText = new StringBuilder();
        for (Msg msg : messages) {
            if (msg.getTextContent() != null) {
                fullText.append(msg.getTextContent()).append(" ");
            }
        }

        return detectScene(fullText.toString());
    }
}
