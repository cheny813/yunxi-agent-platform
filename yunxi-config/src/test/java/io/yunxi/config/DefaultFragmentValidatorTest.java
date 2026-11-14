package io.yunxi.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@link DefaultFragmentValidator} 单元测试：校验配置片段的必填项
 * （id / kind / content）缺失或空白时抛 {@link IllegalArgumentException}，
 * 合法片段放行。
 */
class DefaultFragmentValidatorTest {

    private final DefaultFragmentValidator validator = new DefaultFragmentValidator();

    @Test
    void validFragmentPasses() {
        assertDoesNotThrow(
                () -> validator.validate(new ConfigFragment("f1", "model", Map.of("k", "v"))));
    }

    @Test
    void nullFragmentThrows() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> validator.validate(null));
        assertEquals("fragment is null", e.getMessage());
    }

    @Test
    void blankIdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(new ConfigFragment("  ", "model", Map.of())));
    }

    @Test
    void blankKindThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(new ConfigFragment("f1", "", Map.of())));
    }

    @Test
    void nullContentThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(new ConfigFragment("f1", "model", null)));
    }
}
