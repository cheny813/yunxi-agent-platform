package io.yunxi.platform.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.yunxi.platform.framework.websocket.WebSocketAgentscopeProperties;

/**
 * AgentConfig 配置验证测试
 * 
 * 测试Spring配置属性是否正确绑定到Java类
 */
@SpringBootTest(classes = ConfigValidationTest.TestConfig.class)
@ExtendWith(SpringExtension.class)
@ActiveProfiles("test")
class ConfigValidationTest {

    @EnableConfigurationProperties(WebSocketAgentscopeProperties.class)
    static class TestConfig {
        // 空配置类，用于启用配置属性绑定
    }

    @Autowired(required = false)
    private WebSocketAgentscopeProperties webSocketAgentscopeProperties;

    @Test
    void testWebSocketAgentscopePropertiesBinding() {
        // 验证WebSocketAgentscopeProperties配置绑定
        assertNotNull(webSocketAgentscopeProperties, "WebSocketAgentscopeProperties应该被自动注入");

        // 验证配置值是否正确绑定
        assertEquals(60000L, webSocketAgentscopeProperties.getDefaultAgentTimeout(),
                "默认Agent超时时间应该为60秒");
        assertEquals(30000, webSocketAgentscopeProperties.getConnectionTimeout(),
                "连接超时应该为30秒");
        assertEquals(60000, webSocketAgentscopeProperties.getReadTimeout(),
                "读取超时应该为60秒");
        assertEquals(30000, webSocketAgentscopeProperties.getWriteTimeout(),
                "写入超时应该为30秒");
    }

    @Test
    void testWebSocketConfigurationValuesAreWithinReasonableRanges() {
        // 验证WebSocket配置值在合理范围内
        assertTrue(webSocketAgentscopeProperties.getDefaultAgentTimeout() > 0,
                "WebSocket超时时间应该是正数");
        assertTrue(webSocketAgentscopeProperties.getDefaultAgentTimeout() <= 120000,
                "WebSocket默认超时时间不应该超过2分钟");

        assertTrue(webSocketAgentscopeProperties.getConnectionTimeout() > 0,
                "WebSocket连接超时应该是正数");
        assertTrue(webSocketAgentscopeProperties.getConnectionTimeout() <= 60000,
                "WebSocket连接超时不应该超过60秒");

        assertTrue(webSocketAgentscopeProperties.getReadTimeout() > webSocketAgentscopeProperties.getConnectionTimeout(),
                "WebSocket读取超时应该大于连接超时");
    }

    @Test
    void testWebSocketPropertiesNotNullFieldValidation() {
        // 验证WebSocket关键字段不为null
        assertNotNull(webSocketAgentscopeProperties.getDefaultAgentTimeout(),
                "WebSocket默认Agent超时时间不应该为null");
        assertNotNull(webSocketAgentscopeProperties.getConnectionTimeout(),
                "WebSocket连接超时不应该为null");
        assertNotNull(webSocketAgentscopeProperties.getReadTimeout(),
                "WebSocket读取超时不应该为null");
        assertNotNull(webSocketAgentscopeProperties.getWriteTimeout(),
                "WebSocket写入超时不应该为null");
    }

    @Test
    void testWebSocketConfigurationConsistency() {
        // 验证WebSocket配置一致性
        assertTrue(webSocketAgentscopeProperties.getReadTimeout() >= webSocketAgentscopeProperties.getWriteTimeout(),
                "WebSocket读取超时应该大于或等于写入超时");
        assertTrue(webSocketAgentscopeProperties.getDefaultAgentTimeout() >= webSocketAgentscopeProperties.getReadTimeout(),
                "WebSocket默认Agent超时应该大于或等于读取超时");
    }

    @Test
    void testWebSocketConfigurationReloadAbility() {
        // 验证WebSocket配置能够重新加载（无状态依赖）
        long initialTimeout = webSocketAgentscopeProperties.getDefaultAgentTimeout();

        // 模拟重新赋值（实际中通过@RefreshScope实现）
        webSocketAgentscopeProperties.setDefaultAgentTimeout(initialTimeout + 1000);

        assertEquals(initialTimeout + 1000, webSocketAgentscopeProperties.getDefaultAgentTimeout(),
                "WebSocket属性应该能够被重新设置");

        // 恢复原始值
        webSocketAgentscopeProperties.setDefaultAgentTimeout(initialTimeout);
    }
}