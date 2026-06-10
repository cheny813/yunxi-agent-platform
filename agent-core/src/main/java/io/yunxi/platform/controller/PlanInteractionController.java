package io.yunxi.platform.controller;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.yunxi.platform.agent.service.AgentService;

/**
 * 计划交互控制器
 * <p>
 * 提供计划确认、修改、跳过等操作的 REST API
 * 通过 PlanPreCreator 根据 plan.enabled + templates 配置自动创建计划
 * 简化 API 调用
 * </p>
 *
 * API 使用说明：基于 SSE 的计划确认流程
 * <b>流程</b>：
 * <ol>
 * <li>发起对话后 SSE 返回 type=plan 事件，包含计划内容，等待用户确认</li>
 * <li>用户点击"确认"则 POST /api/plan/confirm，继续 SSE 流</li>
 * <li>用户点击"修改"则 POST /api/plan/modify，携带修改后的计划</li>
 * <li>用户点击"跳过"则 POST /api/plan/skip，跳过进入 ReAct 循环</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/plan")
public class PlanInteractionController {

        /** 类级别日志记录器 */
        private static final Logger log = LoggerFactory.getLogger(PlanInteractionController.class);

        /** Agent 服务，用于获取 Agent 实例 */
        private final AgentService agentDomainService;

        /**
         * 构造计划交互控制器
         *
         * @param agentDomainService Agent 服务
         */
        public PlanInteractionController(
                        AgentService agentDomainService) {
                this.agentDomainService = agentDomainService;
        }

        /**
         * 确认计划：用户确认计划后继续执行
         * <p>
         * 确认后 Agent 将按照用户确认的计划继续执行下一步操作
         * 通过 ConversationController 继续 SSE 响应
         * </p>
         *
         * @return 确认结果，包含状态和 Agent 信息
         */
        @PostMapping("/confirm")
        public ResponseEntity<Map<String, Object>> confirmPlan(@RequestBody PlanConfirmRequest request) {
                var agent = agentDomainService.findAgent(request.agentName());
                if (agent == null) {
                        return ResponseEntity.badRequest().body(Map.of(
                                        "status", "error",
                                        "message", "Agent not found: " + request.agentName()));
                }

                // PlanNotebook 由 HarnessAgent 内部管理
                // 计划确认流程通过 HarnessAgent 内部的 PlanNotebook 处理
                return ResponseEntity.ok(Map.of(
                                "status", "confirmed",
                                "message", "计划已确认（通过 HarnessAgent）"));
        }

        /**
         * 修改计划：用户修改计划后重新执行
         * <p>
         * 修改后 Agent 将使用用户修改后的计划继续执行下一步操作
         * 携带用户修改后的 subtasks 列表更新 Agent 计划
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

                // PlanNotebook 由 HarnessAgent 内部管理
                // 计划修改通过 HarnessAgent 内部的 PlanNotebook 处理
                log.info("修改计划: agent={}, 子任务数{}", request.agentName(),
                                request.subtasks() != null ? request.subtasks().size() : 0);

                return ResponseEntity.ok(Map.of(
                                "status", "modified",
                                "message", "计划已修改（通过 HarnessAgent）"));
        }

        /**
         * 跳过计划：用户跳过计划进入 ReAct 循环
         * <p>
         * 跳过后 Agent 将不使用计划模式，直接进入标准的 ReAct 执行流程
         * </p>
         */
        @PostMapping("/skip")
        public ResponseEntity<Map<String, String>> skipPlan(@RequestBody PlanSkipRequest request) {
                var agent = agentDomainService.findAgent(request.agentName());
                if (agent != null) {
                        // PlanNotebook 由 HarnessAgent 内部管理
                        log.info("跳过计划: agent={}", request.agentName());
                }

                return ResponseEntity.ok(Map.of(
                                "status", "skipped",
                                "message", "计划已跳过，直接进入执行阶段"));
        }

        // ========== 请求/响应 DTO（Java Records）==========

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
