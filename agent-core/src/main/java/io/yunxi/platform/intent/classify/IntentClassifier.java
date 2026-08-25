package io.yunxi.platform.intent.classify;

import java.util.List;

import io.yunxi.platform.intent.Entity;
import io.yunxi.platform.intent.Intent;

/**
 * 意图分类 SPI（M1 规则实现：三级链场景检测 + 意图树关键词匹配）。
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public interface IntentClassifier {

    /**
     * 仅三级检测链，输出场景名（供 IntentResult.sceneName 与关闭开关时的兜底）。
     */
    String detectSceneName(String query);

    /**
     * 意图分类：sceneName 由调用方传入（门面先调 detectSceneName 一次，结果复用，避免三级链跑两遍）。
     */
    Intent classify(String query, List<Entity> entities, String sceneName);
}
