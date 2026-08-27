package io.yunxi.platform.intent.classify;

import java.util.List;

import io.yunxi.platform.intent.Entity;
import io.yunxi.platform.intent.Intent;
import io.yunxi.platform.intent.domain.DomainRuntime;

/**
 * 意图分类 SPI。
 *
 * <p>实现注册为 Spring Bean；引擎按优先级（低者优先）顺序调用，首个非 unknown 即命中。
 * classify 携带域运行时（意图树快照）；matchedBy 由实现标记
 * （RULE / LLM / LLM-CACHE / RULE-FALLBACK / NONE），废弃 RULE_TREE。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public interface IntentClassifier {

    /**
     * 分类（意图树数据自 runtime.tree() 快照读取）。
     *
     * @param query     查询文本
     * @param entities  NER 实体
     * @param sceneName 三级链场景名（GENERAL 或自定义场景）
     * @param runtime   当前域运行时（null 时返回 unknown）
     * @return 意图（unknown 表示未命中，继续下一分类器）
     */
    Intent classify(String query, List<Entity> entities, String sceneName, DomainRuntime runtime);

    /**
     * 场景检测（三级链，域无关）。
     *
     * @param query 查询文本
     * @return 场景名（无命中返回 GENERAL）
     */
    String detectSceneName(String query);
}
