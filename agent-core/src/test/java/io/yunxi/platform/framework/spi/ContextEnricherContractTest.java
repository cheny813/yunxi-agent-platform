package io.yunxi.platform.framework.spi;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextEnricher SPI 契约测试
 * <p>
 * 所有实现者应继承此类并实现 {@link #createInstance()} 和 {@link #supportedContext()}。
 * </p>
 */
public abstract class ContextEnricherContractTest extends AbstractSpiContractTest<ContextEnricher> {

    @Override
    protected Class<ContextEnricher> spiType() {
        return ContextEnricher.class;
    }

    /** 提供一个能让 supports() 返回 true 的 context，子类可覆盖 */
    protected Map<String, Object> supportedContext() {
        return Map.of("test", "value");
    }

    @Test
    void supports_nullContext_shouldReturnFalse() {
        assertFalse(createInstance().supports(null), "supports(null) 必须返回 false");
    }

    @Test
    void supports_emptyContext_shouldNotThrow() {
        assertDoesNotThrow(() -> createInstance().supports(Map.of()),
                "supports(空Map) 不应抛异常");
    }

    @Test
    void enrich_shouldNotThrowWithSupportedContext() {
        ContextEnricher instance = createInstance();
        if (instance.supports(supportedContext())) {
            assertDoesNotThrow(() -> instance.enrich(supportedContext(), "test query"),
                    "enrich() 在 supports=true 的情况下不应抛异常");
        }
    }

    @Test
    void appendPrompt_shouldNotThrowWithSupportedContext() {
        ContextEnricher instance = createInstance();
        if (instance.supports(supportedContext())) {
            assertDoesNotThrow(() -> instance.appendPrompt(supportedContext()),
                    "appendPrompt() 在 supports=true 的情况下不应抛异常");
        }
    }

    @Test
    void formatKey_nonNullKey_shouldReturnNonNull() {
        String result = createInstance().formatKey("testKey", "testValue");
        assertNotNull(result, "formatKey() 不应返回 null");
    }
}
