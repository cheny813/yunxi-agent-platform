package io.yunxi.fabric.aistio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * fabric aistio 配置验证：{@link AistioIntegrationProperties} 承载 yunxi 侧连接参数，默认值必须安全——
 * 默认禁用避免无控制面 URL 时 Bean 创建失败；默认 http 注册开、grpc 关、事件观测默认关闭。
 */
class AistioIntegrationPropertiesTest {

    @Test
    void defaults_areSafe() {
        AistioIntegrationProperties p = new AistioIntegrationProperties();
        assertFalse(p.isEnabled(), "默认禁用：避免无控制面 URL 时 Bean 创建失败");
        assertEquals("default", p.getNamespace());
        assertTrue(p.isStartHttpRegister(), "默认开启 HTTP 自注册");
        assertFalse(p.isStartGrpc(), "默认关闭 gRPC");
        assertFalse(p.isEnableEvents(), "事件观测默认关闭");
        assertEquals(40001, p.getContractHttpPort());
    }

    @Test
    void setters_mapToGetters() {
        AistioIntegrationProperties p = new AistioIntegrationProperties();
        p.setEnabled(true);
        p.setControlPlaneUrl("http://cp:8081");
        p.setBaseUrl("http://host:40001");
        p.setInternalToken("tok");
        p.setInstanceKey("k1");
        p.setWorkspaceRoot("/tmp/ws");
        p.setContractHttpPort(50001);
        p.setEnableEvents(true);
        p.setStartGrpc(true);

        assertTrue(p.isEnabled());
        assertEquals("http://cp:8081", p.getControlPlaneUrl());
        assertEquals("http://host:40001", p.getBaseUrl());
        assertEquals("tok", p.getInternalToken());
        assertEquals("k1", p.getInstanceKey());
        assertEquals("/tmp/ws", p.getWorkspaceRoot());
        assertEquals(50001, p.getContractHttpPort());
        assertTrue(p.isEnableEvents());
        assertTrue(p.isStartGrpc());
    }
}
