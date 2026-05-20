package io.yunxi.platform.framework.embedding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ModelProviderFactory 模型工厂单元测试
 */
class ModelProviderFactoryTest {

    private final ModelProviderFactory factory = new ModelProviderFactory();

    @Test
    void testCreateProviderForOpenAI() {
        ModelConfig config = new ModelConfig();
        config.setProvider("openai");
        config.setApiKey("test-api-key");
        config.setModelName("gpt-4");
        config.setBaseUrl("https://api.openai.com/");

        ChatModelProvider provider = factory.createProvider(config);

        assertNotNull(provider);
        assertEquals("openai", provider.getProvider());
    }

    @Test
    void testCreateProviderForBaidu() {
        ModelConfig config = new ModelConfig();
        config.setProvider("baidu");
        config.setApiKey("test-api-key");
        config.setModelName("ERNIE-Bot-turbo");

        ChatModelProvider provider = factory.createProvider(config);

        assertNotNull(provider);
        assertEquals("baidu", provider.getProvider());
    }

    @Test
    void testCreateProviderWithEmptyProvider() {
        ModelConfig config = new ModelConfig();
        config.setProvider("");
        config.setApiKey("test-api-key");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, 
            () -> factory.createProvider(config));
        assertNotNull(exception.getMessage());
        assertTrue(exception.getMessage().contains("不支持的模型提供商"));
    }

    @Test
    void testCreateProviderWithNullProvider() {
        ModelConfig config = new ModelConfig();
        config.setProvider(null);
        config.setApiKey("test-api-key");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, 
            () -> factory.createProvider(config));
        assertNotNull(exception.getMessage());
        assertTrue(exception.getMessage().contains("不支持的模型提供商"));
    }

    @Test
    void testCreateProviderWithInvalidProvider() {
        ModelConfig config = new ModelConfig();
        config.setProvider("unsupported-provider");
        config.setApiKey("test-api-key");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, 
            () -> factory.createProvider(config));
        assertNotNull(exception.getMessage());
        assertTrue(exception.getMessage().contains("不支持的模型提供商"));
    }

    @Test
    void testCreateProviderWithMissingApiKey() {
        ModelConfig config = new ModelConfig();
        config.setProvider("openai");
        config.setApiKey("");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, 
            () -> factory.createProvider(config));
        assertNotNull(exception.getMessage());
    }

    @Test
    void testCreateProviderWithNullApiKey() {
        ModelConfig config = new ModelConfig();
        config.setProvider("openai");
        config.setApiKey(null);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, 
            () -> factory.createProvider(config));
        assertNotNull(exception.getMessage());
    }

    @Test
    void testCreateProviderWithValidDefaults() {
        ModelConfig config = new ModelConfig();
        config.setProvider("openai");
        config.setApiKey("test-api-key");
        // 不设置模型名称，应该可以正常创建

        ChatModelProvider provider = factory.createProvider(config);

        assertNotNull(provider);
        assertEquals("openai", provider.getProvider());
    }

    @Test
    void testCreateProviderWithCustomBaseUrl() {
        ModelConfig config = new ModelConfig();
        config.setProvider("openai");
        config.setApiKey("test-api-key");
        config.setBaseUrl("https://custom.openai.example.com/");

        ChatModelProvider provider = factory.createProvider(config);

        assertNotNull(provider);
        assertEquals("openai", provider.getProvider());
    }

    @Test
    void testCreateProviderWithBaiduRequiringModelName() {
        ModelConfig config = new ModelConfig();
        config.setProvider("baidu");
        config.setApiKey("test-api-key");
        config.setModelName(""); // 空模型名称

        // 百度模型不需要SecretKey，但需要模型名称
        ChatModelProvider provider = factory.createProvider(config);
        assertNotNull(provider);
        assertEquals("baidu", provider.getProvider());
    }

    @Test
    void testCreateProviderWithBaiduNullModelName() {
        ModelConfig config = new ModelConfig();
        config.setProvider("baidu");
        config.setApiKey("test-api-key");
        config.setModelName(null); // null 模型名称

        // 百度模型需要模型名称，但提供者会处理空值
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, 
            () -> factory.createProvider(config));
        assertNotNull(exception.getMessage());
    }

    @Test
    void testModelProviderLifecycle() {
        ModelConfig config = new ModelConfig();
        config.setProvider("openai");
        config.setApiKey("test-api-key");
        
        // 创建多个提供商实例
        ChatModelProvider provider1 = factory.createProvider(config);
        ChatModelProvider provider2 = factory.createProvider(config);
        
        assertNotNull(provider1);
        assertNotNull(provider2);
        
        // 验证是不同的实例
        assertNotSame(provider1, provider2);
        
        // 验证相同配置产生功能相同的提供商
        assertEquals(provider1.getProvider(), provider2.getProvider());
    }

    @Test
    void testProviderSupportedModels() {
        // 测试支持的模型配置
        ModelConfig openAIConfig = new ModelConfig();
        openAIConfig.setProvider("openai");
        openAIConfig.setApiKey("test-api-key");
        openAIConfig.setModelName("gpt-3.5-turbo");
        
        ModelConfig baiduConfig = new ModelConfig();
        baiduConfig.setProvider("baidu");
        baiduConfig.setApiKey("test-api-key");
        baiduConfig.setModelName("ERNIE-Bot-4");
        
        ChatModelProvider openAIProvider = factory.createProvider(openAIConfig);
        ChatModelProvider baiduProvider = factory.createProvider(baiduConfig);
        
        assertNotNull(openAIProvider);
        assertNotNull(baiduProvider);
        assertEquals("openai", openAIProvider.getProvider());
        assertEquals("baidu", baiduProvider.getProvider());
    }

    @Test
    void testErrorMessages() {
        ModelConfig invalidConfig = new ModelConfig();
        invalidConfig.setProvider("unknown");
        invalidConfig.setApiKey("test");
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, 
            () -> factory.createProvider(invalidConfig));
        
        assertNotNull(exception.getMessage());
        assertTrue(exception.getMessage().contains("provider") || 
                  exception.getMessage().contains("unknown") ||
                  exception.getMessage().contains("不支持"));
    }
}