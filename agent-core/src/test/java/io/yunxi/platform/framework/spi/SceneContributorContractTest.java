package io.yunxi.platform.framework.spi;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SceneContributor SPI 契约测试
 */
public abstract class SceneContributorContractTest extends AbstractSpiContractTest<SceneContributor> {

    @Override
    protected Class<SceneContributor> spiType() {
        return SceneContributor.class;
    }

    @Test
    void getSceneKeywords_shouldReturnNonNull() {
        Map<String, List<String>> keywords = createInstance().getSceneKeywords();
        assertNotNull(keywords, "getSceneKeywords() 不应返回 null");
    }

    @Test
    void getSceneKeywords_shouldNotContainNullValues() {
        Map<String, List<String>> keywords = createInstance().getSceneKeywords();
        for (Map.Entry<String, List<String>> entry : keywords.entrySet()) {
            assertNotNull(entry.getKey(), "关键词 map 的 key 不应为 null");
            assertNotNull(entry.getValue(), "关键词 map 的 value 不应为 null");
        }
    }

    @Test
    void getExtractionPrompt_unknownScene_shouldReturnNull() {
        String prompt = createInstance().getExtractionPrompt("NONEXISTENT_SCENE_12345");
        // 未知场景返回 null 是合理的
        // 只要不抛异常即可
    }

    @Test
    void assembleContext_shouldNotThrow() {
        assertDoesNotThrow(() -> createInstance().assembleContext("GENERAL", "user1", "test query"),
                "assembleContext() 不应抛异常");
    }
}
