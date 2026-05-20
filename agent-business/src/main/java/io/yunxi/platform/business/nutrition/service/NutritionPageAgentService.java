package io.yunxi.platform.business.nutrition.service;

import io.yunxi.platform.framework.pageagent.PageAgentService;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 营养业务 Page Agent 服务
 * <p>
 * 封装食谱配平相关的 Page Agent 操作，提供业务层简单调用的 API。
 * 业务层只需传入数据和目标页面，即可自动完成表单填写、配平操作。
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>
 * {@code @Autowired}
 * private NutritionPageAgentService nutritionPageAgentService;
 *
 * // 食谱生成 + 自动填写
 * RecipeFillResult result = nutritionPageAgentService.generateAndFill(
 *     new RecipeFillRequest()
 *         .setSchoolId(12345)
 *         .setNsId(1)
 *         .setMaleCount(100)
 *         .setFemaleCount(100)
 *         .setAgeGroup("6-8岁")
 *         .setTargetFormUrl("https://example.com/recipe/form")
 * );
 * </pre>
 */
@Slf4j
@Service
public class NutritionPageAgentService {

    /** Page Agent 服务，用于自动填写页面表单 */
    @Autowired
    private PageAgentService pageAgentService;

    /** 食谱流式生成服务 */
    @Autowired
    private RecipeStreamService recipeStreamService;

    /**
     * 生成食谱并自动填写表单
     * <p>
     * 完整流程：
     * 1. 调用后端 API 生成食谱数据
     * 2. 使用 Page Agent 导航到目标页面
     * 3. 自动填写表单
     * 4. 可选：执行营养配平
     * </p>
     *
     * @param request 请求参数
     * @return 执行结果
     */
    public RecipeFillResult generateAndFill(RecipeFillRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("开始食谱生成+填写流程，目标页面: {}", request.getTargetFormUrl());

        try {
            // Step 1: 调用后端 API 生成食谱数据
            log.info("Step 1: 生成食谱数据...");
            Map<String, Object> recipeData = generateRecipeData(request);
            if (recipeData == null) {
                RecipeFillResult result = new RecipeFillResult();
                result.setSuccess(false);
                result.setError("食谱生成失败");
                return result;
            }
            log.info("食谱数据生成完成，数据大小: {} 字符", recipeData.toString().length());

            // Step 2: 创建 Page Agent 会话并填写表单
            String sessionId = "recipe-fill-" + System.currentTimeMillis();
            PageAgentService.PageAgentSession session = pageAgentService.createSession(sessionId);

            String targetUrl = request.getTargetFormUrl();
            if (targetUrl == null || targetUrl.isBlank()) {
                RecipeFillResult result = new RecipeFillResult();
                result.setSuccess(false);
                result.setError("未指定目标表单页面");
                return result;
            }

            log.info("Step 2: 自动填写表单，目标: {}", targetUrl);

            // 构建表单填写任务
            String task = buildFillTask(request, recipeData);
            PageAgentService.PageAgentRequest fillRequest = new PageAgentService.PageAgentRequest();
            fillRequest.setSessionId(sessionId);
            fillRequest.setTask(task);
            fillRequest.setTargetUrl(targetUrl);
            fillRequest.setData(recipeData);

            PageAgentService.PageAgentResult fillResult = pageAgentService.execute(fillRequest);

            // Step 3: 可选执行配平
            if (request.getAutoBalance() != null && request.getAutoBalance() && request.getBalanceFormUrl() != null) {
                log.info("Step 3: 执行营养配平...");
                executeBalance(sessionId, request.getBalanceFormUrl(), recipeData);
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("食谱生成+填写流程完成，耗时: {}ms", duration);

            RecipeFillResult result = new RecipeFillResult();
            result.setSuccess(true);
            result.setRecipeData(recipeData);
            result.setSessionId(sessionId);
            result.setDuration(duration);
            return result;

        } catch (Exception e) {
            log.error("食谱生成+填写流程失败: {}", e.getMessage(), e);
            RecipeFillResult result = new RecipeFillResult();
            result.setSuccess(false);
            result.setError(e.getMessage());
            return result;
        }
    }

    /**
     * 仅填写表单（不生成）
     *
     * @param targetFormUrl 目标页面
     * @param recipeData     食谱数据
     * @return 执行结果
     */
    public RecipeFillResult fillForm(String targetFormUrl, Map<String, Object> recipeData) {
        if (targetFormUrl == null || recipeData == null) {
            RecipeFillResult result = new RecipeFillResult();
            result.setSuccess(false);
            result.setError("参数不完整");
            return result;
        }

        String sessionId = "recipe-fill-" + System.currentTimeMillis();
        PageAgentService.PageAgentResult result = pageAgentService.navigateAndSubmit(
                targetFormUrl,
                recipeData,
                "保存"
        );

        if (result.isSuccess()) {
            RecipeFillResult fillResult = new RecipeFillResult();
            fillResult.setSuccess(true);
            fillResult.setSessionId(sessionId);
            return fillResult;
        } else {
            RecipeFillResult fillResult = new RecipeFillResult();
            fillResult.setSuccess(false);
            fillResult.setError(result.getError());
            return fillResult;
        }
    }

    /**
     * 仅执行配平
     *
     * @param balanceFormUrl 配平页面
     * @param recipeData      食谱数据
     * @return 执行结果
     */
    public RecipeFillResult runBalance(String balanceFormUrl, Map<String, Object> recipeData) {
        if (balanceFormUrl == null || recipeData == null) {
            RecipeFillResult result = new RecipeFillResult();
            result.setSuccess(false);
            result.setError("参数不完整");
            return result;
        }

        String sessionId = "recipe-balance-" + System.currentTimeMillis();

        // 导航到配平页面并执行
        String task = "导航到配平页面，分析当前营养数据，执行营养配平计算，点击提交按钮";
        PageAgentService.PageAgentRequest request = new PageAgentService.PageAgentRequest();
        request.setSessionId(sessionId);
        request.setTask(task);
        request.setTargetUrl(balanceFormUrl);
        request.setData(recipeData);

        PageAgentService.PageAgentResult result = pageAgentService.execute(request);

        if (result.isSuccess()) {
            RecipeFillResult balanceResult = new RecipeFillResult();
            balanceResult.setSuccess(true);
            balanceResult.setSessionId(sessionId);
            return balanceResult;
        } else {
            RecipeFillResult balanceResult = new RecipeFillResult();
            balanceResult.setSuccess(false);
            balanceResult.setError(result.getError());
            return balanceResult;
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 生成食谱数据（调用 RecipeStreamService）
     *
     * @param request 食谱填写请求参数
     * @return 生成的食谱数据 Map
     */
    private Map<String, Object> generateRecipeData(RecipeFillRequest request) {
        // 这里可以调用现有的 RecipeStreamService
        // 返回食谱数据 Map
        // 简化示例，实际需要根据 RecipeStreamService 的 API 调整

        log.info("调用食谱生成服务，参数: schoolId={}, nsId={}, ageGroup={}",
                request.getSchoolId(), request.getNsId(), request.getAgeGroup());

        // 示例返回，实际需要调用真实 API
        // 返回后端生成的食谱数据
        return Map.of(
                "name", "测试食谱",
                "schoolId", request.getSchoolId(),
                "nsId", request.getNsId(),
                "ageGroup", request.getAgeGroup(),
                "maleCount", request.getMaleCount(),
                "femaleCount", request.getFemaleCount()
        );
    }

    /**
     * 构建表单填写任务描述
     *
     * @param request    食谱填写请求参数
     * @param recipeData 食谱数据
     * @return 任务描述字符串
     */
    private String buildFillTask(RecipeFillRequest request, Map<String, Object> recipeData) {
        StringBuilder sb = new StringBuilder();
        sb.append("请执行以下操作：\n");
        sb.append("1. 导航到页面\n");
        sb.append("2. 填写食谱名称: ").append(recipeData.get("name")).append("\n");
        sb.append("3. 填写营养相关信息\n");
        sb.append("4. 点击保存按钮\n");

        if (request.getCustomPrompt() != null && !request.getCustomPrompt().isBlank()) {
            sb.append("额外要求: ").append(request.getCustomPrompt()).append("\n");
        }

        return sb.toString();
    }

    /**
     * 执行营养配平
     *
     * @param sessionId      会话ID
     * @param balanceFormUrl 配平页面 URL
     * @param recipeData     食谱数据
     */
    private void executeBalance(String sessionId, String balanceFormUrl, Map<String, Object> recipeData) {
        String task = "分析页面上的营养数据，执行配平计算，点击提交按钮完成配平";
        PageAgentService.PageAgentRequest request = new PageAgentService.PageAgentRequest();
        request.setSessionId(sessionId);
        request.setTask(task);
        request.setTargetUrl(balanceFormUrl);
        request.setData(recipeData);

        pageAgentService.execute(request);
    }

    // ==================== DTO ====================

    /**
     * 食谱填写请求
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecipeFillRequest {
        /**
         * 学校ID
         */
        private Long schoolId;

        /**
         * 营养标准ID
         */
        private Integer nsId;

        /**
         * 男生人数
         */
        private Integer maleCount;

        /**
         * 女生人数
         */
        private Integer femaleCount;

        /**
         * 年龄阶段（如"6-8岁"）
         */
        private String ageGroup;

        /**
         * 目标表单页面 URL
         */
        private String targetFormUrl;

        /**
         * 配平页面 URL（可选）
         */
        private String balanceFormUrl;

        /**
         * 是否自动执行配平
         */
        private Boolean autoBalance;

        /**
         * 自定义提示词
         */
        private String customPrompt;

        /**
         * 生成天数
         */
        private Integer days = 7;
    }

    /**
     * 食谱填写结果
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecipeFillResult {
        /** 是否成功 */
        private boolean success;
        /** 错误信息 */
        private String error;
        /** 食谱数据 */
        private Map<String, Object> recipeData;
        /** 会话ID */
        private String sessionId;
        /** 执行耗时（毫秒） */
        private long duration;

        /**
         * 创建成功结果
         *
         * @return 成功的 RecipeFillResult 实例
         */
        public static RecipeFillResult success() {
            RecipeFillResult result = new RecipeFillResult();
            result.setSuccess(true);
            return result;
        }

        /**
         * 创建失败结果
         *
         * @param error 错误信息
         * @return 失败的 RecipeFillResult 实例
         */
        public static RecipeFillResult fail(String error) {
            RecipeFillResult result = new RecipeFillResult();
            result.setSuccess(false);
            result.setError(error);
            return result;
        }
    }
}