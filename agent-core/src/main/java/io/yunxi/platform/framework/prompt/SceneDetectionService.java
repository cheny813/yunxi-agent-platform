package io.yunxi.platform.framework.prompt;

import io.agentscope.core.message.Msg;
import io.yunxi.platform.framework.memory.MemoryScene;
import io.yunxi.platform.framework.memory.MemorySceneRegistry;
import io.yunxi.platform.framework.profile.ConceptRegistry;
import io.yunxi.platform.framework.spi.SceneContributor;
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
 * 自动检测用户当前对话的场景。优先通过 SceneContributor SPI 检测业务场景，
 * 其次通过 MemorySceneRegistry 检测注册的自定义场景，最后回退到框架内置关键词检测。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 3.0.0
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "true")
public class SceneDetectionService {

    /** 场景检测开关 */
    @Value("${memory.scene-detection.enabled:true}")
    private boolean enabled;

    /** 场景贡献者 SPI 列表 */
    @Autowired
    private ObjectProvider<List<SceneContributor>> sceneContributorsProvider;

    /** 记忆场景注册表 */
    @Autowired
    private MemorySceneRegistry memorySceneRegistry;

    /** 统一概念注册表（可选，业务层通过 YAML 配置领域概念） */
    @Autowired
    private ObjectProvider<ConceptRegistry> conceptRegistryProvider;

    // 个人助手相关关键词（通用场景，保留在框架层）
    private static final List<String> PERSONAL_ASSISTANT_KEYWORDS = List.of(
            "记得", "记得我", "我喜欢", "我不喜欢", "我的习惯", "我的偏好",
            "我的工作", "我的家人", "我的生活", "我的经历", "小时候", "回忆",
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

        // 1. 业务层 SceneContributor 关键词检测
        SceneDetectionResult contributorResult = detectByContributors(query);
        if (!contributorResult.isGeneral()) {
            return contributorResult;
        }

        // 2. MemorySceneRegistry 自定义场景关键词检测
        SceneDetectionResult registryResult = detectByRegistry(query);
        if (!registryResult.isGeneral()) {
            return registryResult;
        }

        // 3. ConceptRegistry 领域概念检测（优先于硬编码关键词）
        SceneDetectionResult conceptResult = detectByConcepts(query);
        if (!conceptResult.isGeneral()) {
            return conceptResult;
        }

        // 4. 框架内置关键词检测（兜底）
        return detectByBuiltinKeywords(query);
    }

    /**
     * 通过 SceneContributor 检测场景
     */
    private SceneDetectionResult detectByContributors(String text) {
        String lowerText = text.toLowerCase();
        List<SceneContributor> contributors = sceneContributorsProvider.getIfAvailable();
        if (contributors != null) {
            for (SceneContributor contributor : contributors) {
                Map<String, List<String>> sceneKeywords = contributor.getSceneKeywords();
                for (Map.Entry<String, List<String>> entry : sceneKeywords.entrySet()) {
                    for (String keyword : entry.getValue()) {
                        if (lowerText.contains(keyword.toLowerCase())) {
                            log.debug("通过 SceneContributor 检测到场景 {}: {}", entry.getKey(), text);
                            return SceneDetectionResult.of(entry.getKey());
                        }
                    }
                }
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
     * <p>
     * 将概念领域映射到 MemoryScene：例如 "nutrition" → CAMPUS_MEAL，"personal" →
     * PERSONAL_ASSISTANT
     * </p>
     */
    private SceneDetectionResult detectByConcepts(String text) {
        if (conceptRegistryProvider.getIfAvailable() == null) {
            return SceneDetectionResult.general();
        }

        Map<String, Double> domains = conceptRegistryProvider.getIfAvailable().detectDomains(text);
        if (domains.isEmpty()) {
            return SceneDetectionResult.general();
        }

        // 取得分最高的领域
        var best = domains.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElse(null);

        if (best != null && best.getValue() > 0) {
            String domain = best.getKey();
            // 将领域名映射到 MemoryScene（领域名作为场景标识）
            log.debug("通过 ConceptRegistry 检测到领域 {}: score={}, text={}", domain, best.getValue(), text);
            return SceneDetectionResult.of(domain);
        }

        return SceneDetectionResult.general();
    }

    /**
     * 通过框架内置关键词检测场景（兜底逻辑）
     * <p>
     * 仅在 ConceptRegistry 未配置时作为兜底使用。
     * </p>
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
