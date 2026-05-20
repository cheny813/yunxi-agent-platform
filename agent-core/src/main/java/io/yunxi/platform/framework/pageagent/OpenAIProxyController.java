package io.yunxi.platform.framework.pageagent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * OpenAI 兼容代理 + Agent 配置控制器
 * <p>
 * 供 page-agent SDK 的 customFetch 调用。
 * SDK 将 LLM 请求发送到 /v1/chat/completions，后端转发到已配置的 LLM 服务。
 * API Key 只存在后端，前端零暴露。
 * </p>
 * <p>
 * /v1/agent/config 端点返回 tool schemas + system prompt，
 * 让前端不需要写任何 LLM 提示词文本。
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@RestController
@RequestMapping("/v1")
public class OpenAIProxyController {

    /** 页面 Agent 服务 */
    @Autowired
    private PageAgentService pageAgentService;

    /**
     * OpenAI 兼容代理接口
     * POST /v1/chat/completions
     */
    @PostMapping("/chat/completions")
    public Map<String, Object> proxyChatCompletion(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "X-Agent-Token", required = false) String token) {
        log.info("收到 OpenAI 兼容代理请求: model={}", request.get("model"));

        try {
            return pageAgentService.proxyChatCompletion(request);
        } catch (Exception e) {
            log.error("OpenAI 代理请求失败", e);
            return Map.of(
                    "error", Map.of("message", e.getMessage(), "type", "server_error"),
                    "status", 500
            );
        }
    }

    /**
     * 获取 Agent 配置（tool schemas + system prompt）
     * GET /v1/agent/config?pageType=recipe-make
     *
     * 前端不需要写任何 LLM 提示词文本，只从此接口获取。
     * 返回格式:
     * {
     *   "systemPrompt": "你是一个营养助手...",
     *   "tools": {
     *     "recipe_balance": { "description": "...", "params": { "request": "string", ... } },
     *     "recipe_extract": { "description": "...", "params": { "format": "string?" } }
     *   }
     * }
     */
    @GetMapping("/agent/config")
    public Map<String, Object> getAgentConfig(
            @RequestParam(value = "pageType", defaultValue = "default") String pageType) {
        return pageAgentService.getAgentConfig(pageType);
    }
}
