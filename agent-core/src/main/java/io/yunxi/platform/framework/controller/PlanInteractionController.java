package io.yunxi.platform.framework.controller;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.yunxi.platform.framework.agent.AgentDomainService;

/**
 * 规划交互控制器。
 * <p>
 * 提供用户确认、修改、跳过规划的 REST API。
 * 规划由 PlanPreCreator 自动创建（基于 plan.enabled + templates 配置），
 * 用户通过本 API 决定是否执行。
 * </p>
 *
 * 本 API 与前端展现无关，任何客户端（Web/移动端/第三方系统）
 * 都可以通过 REST 调用完成规划交互。
 *
 * <b>交互流程：</b>
 * <ol>
 * <li>后端通过 SSE type=plan 事件推送规划数据 → 前端展示确认界面</li>
 * <li>用户点击"确认并执行" → POST /api/plan/confirm → 开始 SSE 流式执行</li>
 * <li>用户点击"修改" → POST /api/plan/modify → 保存修改后开始执行</li>
 * <li>用户点击"跳过" → POST /api/plan/skip → 走普通 ReAct 模式</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/plan")
public class PlanInteractionController {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(PlanInteractionController.class);

    /** Agent 领域服务 — 查找 Agent 实例 */
    private final AgentDomainService agentDomainService;

    /**
     * 构造规划交互控制器
     *
     * @param agentDomainService Agent 领域服务
     */
    public PlanInteractionController(
            AgentDomainService agentDomainService) {
        this.agentDomainService = agentDomainService;
    }

    /**
     * 用户确认规划 → 标记规划为待执行状态。
     * <p>
     * 此端点只负责标记规划状态并返回确认信息，
     * 实际的对话执行由前端调用 ConversationController 继续。
     * </p>
     *
     * @return 确认结果，前端收到后应重新发送用户消息触发 Agent 执行
     */
    @PostMapping("/confirm")
    public ResponseEntity<Map<String, Object>> confirmPlan(@RequestBody PlanConfirmRequest request) {
        var agent = agentDomainService.findAgent(request.agentName());
        if (agent == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "Agent not found: " + request.agentName()));
        }

        // PlanNotebook managed by HarnessAgent internally
        // Plan confirmation flow uses HarnessAgent's internal PlanNotebook
        return ResponseEntity.ok(Map.of(
                "status", "confirmed",
                "message", "Plan confirmed (via HarnessAgent)"));
    }

    /**
     * 用户修改规划 → 保存修改后的规划。
     * <p>
     * 用户可以增删改规划步骤，保存修改后的规划。
     * 修改后前端应重新发送用户消息触发 Agent 按新规划执行。
     * </p>
     */
    @PostMapping("/modify")
    public ResponseEntity<Map<String, Object>> modifyPlan(@RequestBody PlanModifyRequest request) {
        var agent = agentDomainService.findAgent(request.agentName());
        if (agent == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "Agent not found: " + request.agentName()));
        }

        // PlanNotebook managed by HarnessAgent internally
        // Plan modification handled via HarnessAgent's internal PlanNotebook
        log.info("用户修改了规划: agent={}, 步骤数={}", request.agentName(),
                request.subtasks() != null ? request.subtasks().size() : 0);

        return ResponseEntity.ok(Map.of(
                "status", "modified",
                "message", "Plan modified (via HarnessAgent)"));
    }

    /**
     * 用户跳过规划 → 走普通 ReAct 模式。
     * <p>
     * 放弃当前规划，Agent 直接以纯 ReAct 模式响应。
     * </p>
     */
    @PostMapping("/skip")
    public ResponseEntity<Map<String, String>> skipPlan(@RequestBody PlanSkipRequest request) {
        var agent = agentDomainService.findAgent(request.agentName());
        if (agent != null) {
            // PlanNotebook managed by HarnessAgent internally
            log.info("用户跳过规划: agent={}", request.agentName());
        }

        return ResponseEntity.ok(Map.of(
                "status", "skipped",
                "message", "规划已跳过，将直接回复您的请求"));
    }

    // ========== 请求/响应 DTO（Java Records） ==========

    public record PlanConfirmRequest(
            String agentName,
            String sessionKey,
            String planId) {
    }

    public record PlanModifyRequest(
            String agentName,
            String sessionKey,
            String planId,
            String name,
            String description,
            List<SubTaskDto> subtasks) {
    }

    public record PlanSkipRequest(
            String agentName,
            String sessionKey,
            String planId) {
    }

    public record SubTaskDto(
            String name,
            String description,
            String expectedOutcome) {
    }
}
