package io.yunxi.platform.framework.spi;

import java.util.List;
import java.util.Map;

/**
 * 场景贡献器 SPI
 *
 * <p>业务层实现此接口，贡献自定义场景的关键词、提取提示词和上下文组装逻辑。</p>
 * <p>框架层 SceneDetectionService、ConversationMemoryExtractor、MemoryCoordinatorService
 * 通过收集所有实现来支持可扩展的场景体系。</p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public interface SceneContributor {

    /**
     * 贡献的场景关键词
     *
     * @return Map&lt;场景名称, 关键词列表&gt;，如 {"SCHOOL_MEAL": ["食谱", "营养", "菜品"]}
     */
    Map<String, List<String>> getSceneKeywords();

    /**
     * 获取场景的提取提示词（用于 LLM 记忆提取）
     *
     * @param sceneName 场景名称
     * @return 提取提示词，返回 null 表示不提供
     */
    String getExtractionPrompt(String sceneName);

    /**
     * 组装场景上下文
     *
     * @param sceneName 场景名称
     * @param userId    用户ID
     * @param query     用户查询
     * @return 组装后的上下文文本，返回 null 表示不提供
     */
    String assembleContext(String sceneName, String userId, String query);
}
