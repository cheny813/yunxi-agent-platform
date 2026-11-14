package io.yunxi.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link RoutedModel} 便捷构造与字段。 */
class RoutedModelTest {

    @Test
    void compactConstructorDefaultsAutoToFalse() {
        RoutedModel r = new RoutedModel("dashscope:qwen-max", List.of("dashscope:qwen-fallback"));
        assertFalse(r.auto());
        assertEquals("dashscope:qwen-max", r.primaryModelId());
        assertEquals(List.of("dashscope:qwen-fallback"), r.fallbackModelIds());
    }

    @Test
    void threeArgConstructorSetsAutoFlag() {
        RoutedModel r = new RoutedModel("dashscope:qwen-max", List.of(), true);
        assertTrue(r.auto());
    }
}
