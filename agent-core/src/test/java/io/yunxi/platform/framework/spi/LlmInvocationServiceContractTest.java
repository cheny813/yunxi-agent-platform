package io.yunxi.platform.framework.spi;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LlmInvocationService SPI 契约测试
 */
public abstract class LlmInvocationServiceContractTest extends AbstractSpiContractTest<LlmInvocationService> {

    @Override
    protected Class<LlmInvocationService> spiType() {
        return LlmInvocationService.class;
    }

    @Test
    void isAvailable_shouldBeConsistent() {
        LlmInvocationService instance = createInstance();
        // isAvailable() 多次调用应返回相同结果
        boolean first = instance.isAvailable();
        boolean second = instance.isAvailable();
        assertEquals(first, second, "isAvailable() 应返回一致结果");
    }

    @Test
    void invoke_singlePrompt_shouldNotThrowWhenAvailable() {
        LlmInvocationService instance = createInstance();
        if (instance.isAvailable()) {
            assertDoesNotThrow(() -> instance.invoke("test prompt"),
                    "invoke(prompt) 在 isAvailable=true 时不应抛异常");
        }
    }

    @Test
    void invoke_twoPrompt_shouldNotThrowWhenAvailable() {
        LlmInvocationService instance = createInstance();
        if (instance.isAvailable()) {
            assertDoesNotThrow(() -> instance.invoke("system", "user"),
                    "invoke(system, user) 在 isAvailable=true 时不应抛异常");
        }
    }

    @Test
    void invoke_messagesList_shouldNotThrowWhenAvailable() {
        LlmInvocationService instance = createInstance();
        if (instance.isAvailable()) {
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "user", "content", "test"));
            assertDoesNotThrow(() -> instance.invoke(messages),
                    "invoke(messages) 在 isAvailable=true 时不应抛异常");
        }
    }
}
