package io.yunxi.platform.framework.pageagent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Page Agent 控制器
 * <p>
 * 提供 REST API 供前端页面调用 Page Agent 能力
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@RestController
@RequestMapping("/api/page-agent")
public class PageAgentController {

    /** 页面 Agent 服务 */
    @Autowired
    private PageAgentService pageAgentService;

    /**
     * 创建新会话
     * POST /api/page-agent/session
     *
     * @return 会话信息
     */
    @PostMapping("/session")
    public Map<String, Object> createSession(@RequestBody(required = false) Map<String, String> params) {
        String sessionId = params != null ? params.get("sessionId") : null;
        PageAgentService.PageAgentSession session = pageAgentService.createSession(sessionId);
        return Map.of(
                "sessionId", session.getSessionId(),
                "status", session.getStatus(),
                "createdAt", session.getCreatedAt());
    }

    /**
     * 执行任务
     * POST /api/page-agent/execute
     *
     * Body:
     * {
     * "sessionId": "xxx",
     * "task": "点击登录按钮",
     * "targetUrl": "https://...",
     * "data": {...}
     * }
     */
    @PostMapping("/execute")
    public Map<String, Object> execute(@RequestBody PageAgentService.PageAgentRequest request) {
        log.info("收到 Page Agent 执行请求: task={}", request.getTask());

        PageAgentService.PageAgentResult result = pageAgentService.execute(request);

        return Map.of(
                "success", result.isSuccess(),
                "sessionId", result.getSessionId() != null ? result.getSessionId() : "",
                "message", result.getMessage() != null ? result.getMessage() : "",
                "error", result.getError() != null ? result.getError() : "",
                "duration", result.getDuration(),
                "steps", result.getSteps());
    }

    /**
     * 自动填写表单
     * POST /api/page-agent/autofill
     *
     * Body:
     * {
     * "sessionId": "xxx",
     * "targetUrl": "https://...",
     * "formData": {"字段1": "值1", "字段2": "值2"}
     * }
     */
    @PostMapping("/autofill")
    public Map<String, Object> autoFill(@RequestBody Map<String, Object> request) {
        String sessionId = (String) request.get("sessionId");
        String targetUrl = (String) request.get("targetUrl");
        @SuppressWarnings("unchecked")
        Map<String, Object> formData = (Map<String, Object>) request.get("formData");

        PageAgentService.PageAgentResult result = pageAgentService.autoFill(sessionId, targetUrl, formData);

        return Map.of(
                "success", result.isSuccess(),
                "message", result.getMessage() != null ? result.getMessage() : "",
                "error", result.getError() != null ? result.getError() : "");
    }

    /**
     * 导航并提交
     * POST /api/page-agent/navigate-submit
     *
     * Body:
     * {
     * "targetUrl": "https://...",
     * "formData": {...},
     * "submitText": "提交"
     * }
     */
    @PostMapping("/navigate-submit")
    public Map<String, Object> navigateAndSubmit(@RequestBody Map<String, Object> request) {
        String targetUrl = (String) request.get("targetUrl");
        @SuppressWarnings("unchecked")
        Map<String, Object> formData = (Map<String, Object>) request.get("formData");
        String submitText = (String) request.getOrDefault("submitText", "提交");

        PageAgentService.PageAgentResult result = pageAgentService.navigateAndSubmit(targetUrl, formData, submitText);

        return Map.of(
                "success", result.isSuccess(),
                "message", result.getMessage() != null ? result.getMessage() : "",
                "error", result.getError() != null ? result.getError() : "");
    }

    /**
     * 获取会话状态
     * GET /api/page-agent/session/{sessionId}
     */
    @GetMapping("/session/{sessionId}")
    public Map<String, Object> getSession(@PathVariable String sessionId) {
        PageAgentService.PageAgentSession session = pageAgentService.getSession(sessionId);
        if (session == null) {
            return Map.of("error", "会话不存在");
        }
        return Map.of(
                "sessionId", session.getSessionId(),
                "status", session.getStatus(),
                "currentUrl", session.getCurrentUrl() != null ? session.getCurrentUrl() : "",
                "createdAt", session.getCreatedAt(),
                "completedAt", session.getCompletedAt());
    }

    /**
     * 关闭会话
     * DELETE /api/page-agent/session/{sessionId}
     */
    @DeleteMapping("/session/{sessionId}")
    public Map<String, Object> closeSession(@PathVariable String sessionId) {
        pageAgentService.closeSession(sessionId);
        return Map.of("success", true, "message", "会话已关闭");
    }

    /**
     * 聊天接口（供前端页面嵌入的 Page Agent JS 调用）
     * POST /api/page-agent/chat
     *
     * Body:
     * {
     * "message": "帮我填写表单",
     * "pageContext": "页面内容（智能提取或原始HTML）",
     * "url": "当前页面 URL"
     * }
     */
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> request) {
        String message = (String) request.get("message");
        // 兼容新旧字段名：pageContext（智能提取）和 pageHtml（原始HTML）
        String pageContent = (String) request.getOrDefault("pageContext", request.get("pageHtml"));
        String url = (String) request.get("url");

        log.info("收到 Page Agent 聊天请求: message={}", message);

        try {
            // 构建请求
            PageAgentService.PageAgentRequest pageRequest = new PageAgentService.PageAgentRequest();
            pageRequest.setTask(message);
            pageRequest.setTargetUrl(url);
            pageRequest.setData(Map.of("pageHtml",
                    pageContent != null ? pageContent.substring(0, Math.min(pageContent.length(), 10000)) : ""));

            // 调用 PageAgentService 处理请求
            PageAgentService.PageAgentResult result = pageAgentService.execute(pageRequest);

            // 构建返回的 actions（从解析结果中提取）
            List<Map<String, Object>> actions = new ArrayList<>();
            if (result.getData() != null && result.getData().get("parsedActions") != null) {
                String parsedActions = (String) result.getData().get("parsedActions");
                // 尝试解析 JSON
                try {
                    Object parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(parsedActions, Object.class);
                    if (parsed instanceof Map) {
                        Map<String, Object> parsedMap = (Map<String, Object>) parsed;
                        Object actionsObj = parsedMap.get("actions");
                        if (actionsObj instanceof List) {
                            actions = (List<Map<String, Object>>) actionsObj;
                        }
                    }
                } catch (Exception e) {
                    log.debug("解析 actions 失败: {}", e.getMessage());
                }
            }

            return Map.of(
                    "success", result.isSuccess(),
                    "message", result.getMessage() != null ? result.getMessage() : "操作已完成",
                    "reply", result.getMessage() != null ? result.getMessage() : "操作已完成",
                    "actions", actions);
        } catch (Exception e) {
            log.error("Page Agent 聊天处理失败", e);
            return Map.of(
                    "success", false,
                    "error", e.getMessage());
        }
    }
}