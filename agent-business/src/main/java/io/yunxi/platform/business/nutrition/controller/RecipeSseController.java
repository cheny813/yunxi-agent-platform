package io.yunxi.platform.business.nutrition.controller;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.yunxi.platform.business.nutrition.service.RecipeStreamService;
import io.yunxi.platform.shared.spi.SseNotificationProvider;
import io.yunxi.platform.shared.dto.StreamChatRequest;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.UUID;

/**
 * 食谱配平 SSE 控制器
 *
 * <p>
 * 提供基于 SSE 的食谱配平流式输出接口，支持：
 * </p>
 * <ul>
 * <li>AI 流式生成内容</li>
 * <li>任务进度实时推送</li>
 * <li>配平结果回写触发</li>
 * </ul>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/api/recipe-sse")
public class RecipeSseController {

    /** SSE连接管理器，用于创建和管理Server-Sent Events连接 */
    @Autowired
    private SseNotificationProvider emitterManager;

    /** 食谱流式生成服务，处理食谱生成和配平的流式输出 */
    @Autowired
    private RecipeStreamService recipeStreamService;

    /**
     * 创建 SSE 连接（用于任务进度推送）
     *
     * @param sessionId 可选的会话ID，不传则自动生成
     * @return SseEmitter
     */
    @GetMapping(value = "/connect", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect(@RequestParam(required = false) String sessionId) {
        String sid = sessionId != null ? sessionId : UUID.randomUUID().toString();
        log.info("创建 SSE 连接: sessionId={}", sid);

        SseEmitter emitter = emitterManager.createEmitter(sid);

        // 发送连接成功事件
        emitterManager.send(sid, "connected", Map.of(
                "sessionId", sid,
                "message", "SSE 连接已建立"
        ));

        return emitter;
    }

    /**
     * 流式生成食谱（WebFlux Flux 方式）
     *
     * <p>
     * 适用于前端使用 EventSource 或 fetch API 接收流式响应
     * </p>
     *
     * @param request 食谱生成请求
     * @return SSE 事件流
     */
    @PostMapping(value = "/generate-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> generateRecipeStream(@RequestBody RecipeGenerateRequest request) {
        log.info("开始流式生成食谱: schoolId={}, days={}", request.getSchoolId(), request.getDays());

        StreamChatRequest chatRequest = new StreamChatRequest();
        chatRequest.setMessage(request.getPrompt());
        chatRequest.setContextData(Map.of(
                "schoolId", request.getSchoolId(),
                "nsId", request.getNsId(),
                "nscId", request.getNscId(),
                "maleCount", request.getMaleCount(),
                "femaleCount", request.getFemaleCount(),
                "ageGroup", request.getAgeGroup(),
                "days", request.getDays(),
                "mealTypes", request.getMealTypes()
        ));

        return recipeStreamService.streamGenerateRecipe(chatRequest);
    }

    /**
     * 流式生成食谱（SseEmitter 方式）
     *
     * <p>
     * 适用于传统 Spring MVC，支持进度推送和回写触发
     * </p>
     *
     * @param request 食谱生成请求
     * @return SseEmitter
     */
    @PostMapping(value = "/generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateRecipe(@RequestBody RecipeGenerateRequest request) {
        String sessionId = UUID.randomUUID().toString();
        log.info("开始生成食谱: sessionId={}, schoolId={}", sessionId, request.getSchoolId());

        SseEmitter emitter = emitterManager.createEmitter(sessionId);

        // 异步执行生成任务
        recipeStreamService.generateRecipeAsync(sessionId, request);

        return emitter;
    }

    /**
     * 触发表单回写（通过 WebSocket）
     *
     * @param request 回写请求
     * @return 操作结果
     */
    @PostMapping("/trigger-fill")
    public Map<String, Object> triggerFormFill(@RequestBody FormFillRequest request) {
        log.info("触发表单回写: sessionId={}, scene={}", request.getSessionId(), request.getScene());

        // 这里触发 WebSocket 推送，由 FormFillClient 处理
        recipeStreamService.triggerFormFill(request);

        return Map.of(
                "success", true,
                "message", "表单回写指令已发送",
                "sessionId", request.getSessionId()
        );
    }

    /**
     * 获取 SSE 连接状态
     *
     * @param sessionId 会话ID
     * @return 连接状态
     */
    @GetMapping("/status/{sessionId}")
    public Map<String, Object> getStatus(@PathVariable String sessionId) {
        return Map.of(
                "sessionId", sessionId,
                "connected", emitterManager.hasEmitter(sessionId),
                "totalConnections", emitterManager.getActiveConnectionCount()
        );
    }

    // ==================== 配平相关接口 ====================

    /**
     * 流式配平（WebFlux Flux 方式）
     *
     * <p>
     * 实时推送配平进度和结果
     * </p>
     *
     * @param request 配平请求
     * @return SSE 事件流
     */
    @PostMapping(value = "/balance-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> balanceRecipeStream(@RequestBody BalanceRequest request) {
        log.info("开始流式配平: schoolId={}, mode={}", request.getSchoolId(), request.getMode());
        return recipeStreamService.streamBalanceRecipe(request);
    }

    /**
     * 异步配平（SseEmitter 方式）
     *
     * <p>
     * 适用于长时间配平任务，支持进度推送
     * </p>
     *
     * @param request 配平请求
     * @return SseEmitter
     */
    @PostMapping(value = "/balance", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter balanceRecipe(@RequestBody BalanceRequest request) {
        String sessionId = UUID.randomUUID().toString();
        log.info("开始配平: sessionId={}, schoolId={}", sessionId, request.getSchoolId());

        SseEmitter emitter = emitterManager.createEmitter(sessionId);

        // 异步执行配平任务
        recipeStreamService.balanceRecipeAsync(sessionId, request);

        return emitter;
    }

    /**
     * 应用配平结果
     *
     * <p>
     * 将配平后的用量应用到食谱中
     * </p>
     *
     * @param request 应用请求
     * @return 操作结果
     */
    @PostMapping("/apply-balance")
    public Map<String, Object> applyBalance(@RequestBody ApplyBalanceRequest request) {
        log.info("应用配平结果: sessionId={}", request.getSessionId());

        // 通过 SSE 推送应用结果
        if (emitterManager.hasEmitter(request.getSessionId())) {
            emitterManager.send(request.getSessionId(), "balance_applied", Map.of(
                    "success", true,
                    "message", "配平结果已应用",
                    "adjustedCount", request.getAdjustedDishes() != null ? request.getAdjustedDishes().size() : 0
            ));
        }

        return Map.of(
                "success", true,
                "message", "配平结果已应用"
        );
    }

    // ==================== 请求/响应 DTO ====================

    /**
     * 食谱生成请求 DTO
     *
     * <p>包含生成食谱所需的学校信息、人群信息、天数和餐次等参数</p>
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecipeGenerateRequest {
        /**
         * 学校ID
         */
        private Long schoolId;

        /**
         * 营养标准ID
         */
        private Long nsId;

        /**
         * 就餐人群ID
         */
        private Long nscId;

        /**
         * 男生人数
         */
        private Integer maleCount;

        /**
         * 女生人数
         */
        private Integer femaleCount;

        /**
         * 年龄阶段
         */
        private String ageGroup;

        /**
         * 生成天数
         */
        private Integer days;

        /**
         * 餐次类型列表
         */
        private java.util.List<String> mealTypes;

        /**
         * 自定义提示词
         */
        private String prompt;
    }

    /**
     * 表单回写请求 DTO
     *
     * <p>用于触发前端表单的自动填充，支持多种场景的回写操作</p>
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FormFillRequest {
        /**
         * 会话ID
         */
        private String sessionId;

        /**
         * 场景类型：weekly_recipe, recipe, safety, budget
         */
        private String scene;

        /**
         * 表单数据
         */
        private Map<String, Object> formData;

        /**
         * 额外参数
         */
        private Map<String, Object> params;
    }

    /**
     * 配平请求 DTO
     *
     * <p>包含配平所需的学校信息、人群信息、食谱数据和配平模式等参数</p>
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BalanceRequest {
        /**
         * 会话ID
         */
        private String sessionId;

        /**
         * 学校ID
         */
        private Long schoolId;

        /**
         * 营养标准ID
         */
        private Long nsId;

        /**
         * 就餐人群ID
         */
        private Long nscId;

        /**
         * 男生人数
         */
        private Integer maleCount;

        /**
         * 女生人数
         */
        private Integer femaleCount;

        /**
         * 年龄阶段
         */
        private String ageGroup;

        /**
         * 当前食谱数据（按天和餐次组织）
         */
        private Map<String, Object> recipeData;

        /**
         * 配平模式：full（全量配平）、partial（部分配平）
         */
        private String mode;

        /**
         * 目标营养素（可选，指定只配平某些营养素）
         */
        private java.util.List<String> targetNutrients;
    }

    /**
     * 应用配平结果请求 DTO
     *
     * <p>用于将配平后的菜品用量应用到食谱中</p>
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApplyBalanceRequest {
        /**
         * 会话ID
         */
        private String sessionId;

        /**
         * 调整后的菜品列表
         */
        private java.util.List<Map<String, Object>> adjustedDishes;
    }
}
