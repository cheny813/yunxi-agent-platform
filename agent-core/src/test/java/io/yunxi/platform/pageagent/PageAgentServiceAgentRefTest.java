package io.yunxi.platform.pageagent;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 验证 page 配置经 agentRef 引用 AgentScope Agent 定义（去重营养等领域知识）。
 * 构造无需 Model：properties 传 null 时 chatModel 置为 null，不影响配置解析。
 */
class PageAgentServiceAgentRefTest {

    @Test
    void nutritionistResolvesPromptFromAgentRef() {
        PageAgentService service = new PageAgentService(null, null, null);
        Map<String, Object> cfg = service.getAgentConfig("nutritionist");
        assertNotNull(cfg, "nutritionist 配置应存在");
        String sp = (String) cfg.get("systemPrompt");
        assertNotNull(sp, "systemPrompt 不应为空");
        assertTrue(sp.contains("居民营养配餐助手"),
                "systemPrompt 应来自 resident-nutrition-assistant Agent 定义，而非内联副本");
        assertNotNull(cfg.get("tools"), "tools 仍应来自 page 配置");
    }

    @Test
    void unknownPageTypeFallsBackToDefault() {
        PageAgentService service = new PageAgentService(null, null, null);
        Map<String, Object> cfg = service.getAgentConfig("nonexistent-page-type");
        assertNotNull(cfg, "未知 pageType 应回退到默认配置");
        assertNotNull(cfg.get("systemPrompt"));
    }
}
