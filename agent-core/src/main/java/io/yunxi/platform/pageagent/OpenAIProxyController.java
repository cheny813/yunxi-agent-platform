package io.yunxi.platform.pageagent;

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
 * <h3>⚠️ 使用边界（重要，避免误用）</h3>
 * <p><b>/v1/chat/completions 是「裸 LLM 代理通道」，仅做透传转发，不经过任何 Agent 编排、工具调用或评分链路。</b></p>
 * <ul>
 *   <li><b>适合</b>：纯文本补全、表单自动填充、简单文案生成等不需要工具/Agent 的场景。</li>
 *   <li><b>不适合</b>：需要调用工具（如营养评分 {@code evaluate_recipe}）、需要多 Agent 协作、
 *       需要结构化可控输出的业务。这类场景应走 <b>/api/conversations/chat</b>
 *       （指定 agentName + profile，由 Agent 编排工具与评分）。</li>
 * </ul>
 * <p>误用示例：把"营养配餐方案生成"这类本该走 Agent（含真实营养评分）的功能直接打到本端点，
 * 会导致返回内容由 LLM 自由生成、无法保证评分真实准确。前端页面如需评分数据，务必走 Agent 通道。</p>
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
     * GET /v1/agent/config?pageType=business-make
     *
     * 前端不需要写任何 LLM 提示词文本，只从此接口获取。
     * 返回格式:
     * {
     *   "systemPrompt": "你是一个业务助手...",
     *   "tools": {
     *     "report_balance": { "description": "...", "params": { "request": "string", ... } },
     *     "report_extract": { "description": "...", "params": { "format": "string?" } }
     *   }
     * }
     */
    @GetMapping("/agent/config")
    public Map<String, Object> getAgentConfig(
            @RequestParam(value = "pageType", defaultValue = "default") String pageType) {
        return pageAgentService.getAgentConfig(pageType);
    }
}
