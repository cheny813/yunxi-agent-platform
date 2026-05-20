package io.yunxi.platform.business.a2apipeline.service;

import io.yunxi.platform.business.a2apipeline.config.A2ACodePipelineProperties;
import io.yunxi.platform.business.a2apipeline.model.*;
import io.yunxi.platform.shared.dto.StageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pipeline 编排器
 * <p>
 * 编排代码修复流水线的四个阶段：
 * 代码分析 → 代码修复 → 代码审查 → 测试验证
 * </p>
 *
 * <h3>编排策略</h3>
 * <ul>
 * <li>顺序执行：每个 Stage 依赖前一个 Stage 的结果</li>
 * <li>失败终止：任一 Stage 失败则 Pipeline 终止</li>
 * <li>质量门禁：审查阶段不达标时支持重试</li>
 * <li>重试策略：可配置重试次数和重试起始阶段</li>
 * </ul>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
public class PipelineOrchestrator {

    /** 代码分析服务 */
    @Autowired
    private CodeAnalysisService codeAnalysisService;

    /** 代码修复服务 */
    @Autowired
    private CodeFixService codeFixService;

    /** 代码审查服务 */
    @Autowired
    private CodeReviewService codeReviewService;

    /** 测试执行服务 */
    @Autowired
    private TestExecutionService testExecutionService;

    /** 流水线配置属性 */
    @Autowired
    private A2ACodePipelineProperties properties;

    /** 运行中的 Pipeline 实例 */
    private final Map<String, PipelineInstance> runningInstances = new ConcurrentHashMap<>();

    /**
     * 执行完整 Pipeline
     *
     * @param request 代码修复请求
     * @return Pipeline 实例（包含所有阶段结果）
     */
    public PipelineInstance execute(CodeFixRequest request) {
        String instanceId = generateInstanceId();
        return execute(instanceId, request, QualityGateConfig.defaultConfig());
    }

    /**
     * 执行 Pipeline（带质量门禁配置）
     *
     * @param instanceId    实例 ID
     * @param request       代码修复请求
     * @param qualityGate   质量门禁配置
     * @return Pipeline 实例
     */
    public PipelineInstance execute(String instanceId, CodeFixRequest request, QualityGateConfig qualityGate) {
        log.info("[ORCHESTRATOR] Pipeline 启动: instanceId={}, taskId={}", instanceId, request.getTaskId());

        PipelineInstance instance = PipelineInstance.create(instanceId, request.getTaskId());
        PipelineContext context = PipelineContext.create(instanceId, request.getTaskId(), request);
        List<StageResult> stages = new ArrayList<>();
        instance.setStages(stages);

        runningInstances.put(instanceId, instance);

        try {
            instance.setCurrentState(PipelineInstance.PipelineState.RUNNING);

            // Stage 1: 代码分析
            StageResult analysisResult = codeAnalysisService.analyze(context);
            stages.add(analysisResult);
            if (analysisResult.getStatus() == StageResult.StageStatus.FAILED) {
                return failPipeline(instance, "CODE_ANALYSIS 阶段失败", stages);
            }
            context.setCurrentStage("CODE_FIX");

            // Stage 2: 代码修复
            StageResult fixResult = codeFixService.fix(context, analysisResult);
            stages.add(fixResult);

            // 修复失败时尝试重试
            if (fixResult.getStatus() == StageResult.StageStatus.FAILED) {
                fixResult = retryFix(context, analysisResult, fixResult, qualityGate);
                if (fixResult != null) {
                    stages.set(stages.size() - 1, fixResult); // 替换上次修复结果
                }
                if (fixResult == null || fixResult.getStatus() == StageResult.StageStatus.FAILED) {
                    return failPipeline(instance, "CODE_FIX 阶段失败（含重试）", stages);
                }
            }
            context.setCurrentStage("CODE_REVIEW");

            // Stage 3: 代码审查（支持迭代修复）
            StageResult reviewResult = reviewWithIteration(context, fixResult, qualityGate);
            stages.add(reviewResult);
            if (reviewResult.getStatus() == StageResult.StageStatus.FAILED) {
                return failPipeline(instance, "CODE_REVIEW 阶段未通过", stages);
            }
            context.setCurrentStage("TEST_EXECUTION");

            // Stage 4: 测试验证
            StageResult testResult = testExecutionService.execute(context, fixResult, reviewResult);
            stages.add(testResult);
            if (testResult.getStatus() == StageResult.StageStatus.FAILED) {
                return failPipeline(instance, "TEST_EXECUTION 阶段失败", stages);
            }

            // Pipeline 成功完成
            instance.setCurrentState(PipelineInstance.PipelineState.COMPLETED);
            instance.setEndTime(Instant.now());
            instance.setDurationMs(System.currentTimeMillis() - instance.getStartTime().toEpochMilli());

            log.info("[ORCHESTRATOR] Pipeline 完成: instanceId={}, duration={}ms",
                    instanceId, instance.getDurationMs());

            return instance;

        } catch (Exception e) {
            log.error("[ORCHESTRATOR] Pipeline 异常: instanceId={}", instanceId, e);
            return failPipeline(instance, "Pipeline 异常: " + e.getMessage(), stages);
        } finally {
            runningInstances.remove(instanceId);
        }
    }

    /**
     * 审查迭代（修复不通过时重新修复并审查）
     *
     * @param context     流水线上下文
     * @param fixResult   修复结果
     * @param qualityGate 质量门禁配置
     * @return 最终审查结果
     */
    private StageResult reviewWithIteration(PipelineContext context, StageResult fixResult,
            QualityGateConfig qualityGate) {
        int maxIterations = qualityGate.getMaxReviewIterations();

        StageResult reviewResult = codeReviewService.review(context, fixResult);

        for (int i = 0; i < maxIterations; i++) {
            if (reviewResult.getStatus() == StageResult.StageStatus.COMPLETED) {
                return reviewResult; // 审查通过
            }

            // 审查不通过，重新修复
            log.info("[ORCHESTRATOR] 审查不通过，重新修复: iteration={}/{}", i + 1, maxIterations);
            fixResult = codeFixService.fix(context, reviewResult);
            if (fixResult.getStatus() == StageResult.StageStatus.FAILED) {
                return reviewResult; // 修复失败，返回当前审查结果
            }

            // 重新审查
            reviewResult = codeReviewService.review(context, fixResult);
        }

        return reviewResult;
    }

    /**
     * 修复重试
     *
     * @param context            流水线上下文
     * @param analysisResult     分析阶段结果
     * @param originalFixResult  原始修复结果
     * @param qualityGate        质量门禁配置
     * @return 重试后的修复结果，null 表示重试耗尽
     */
    private StageResult retryFix(PipelineContext context, StageResult analysisResult,
            StageResult originalFixResult, QualityGateConfig qualityGate) {
        int maxRetries = properties.getMaxRetries();

        for (int i = 0; i < maxRetries; i++) {
            log.info("[ORCHESTRATOR] 修复重试: attempt={}/{}", i + 1, maxRetries);

            try {
                Thread.sleep(properties.getRetryDelayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }

            StageResult retryResult = codeFixService.fix(context, analysisResult);
            if (retryResult.getStatus() == StageResult.StageStatus.COMPLETED) {
                return retryResult;
            }
        }

        return null;
    }

    /**
     * 将 Pipeline 标记为失败
     *
     * @param instance 流水线实例
     * @param reason   失败原因
     * @param stages   阶段结果列表
     * @return 标记为失败的流水线实例
     */
    private PipelineInstance failPipeline(PipelineInstance instance, String reason,
            List<StageResult> stages) {
        instance.setCurrentState(PipelineInstance.PipelineState.FAILED);
        instance.setEndTime(Instant.now());
        instance.setStages(stages);

        log.warn("[ORCHESTRATOR] Pipeline 失败: instanceId={}, reason={}",
                instance.getInstanceId(), reason);

        return instance;
    }

    /**
     * 获取运行中的 Pipeline 实例
     *
     * @param instanceId 实例 ID
     * @return 运行中的 Pipeline 实例，不存在返回 null
     */
    public PipelineInstance getRunningInstance(String instanceId) {
        return runningInstances.get(instanceId);
    }

    /**
     * 取消 Pipeline
     *
     * @param instanceId 实例 ID
     * @param reason     取消原因
     * @return 取消成功返回 true，实例不存在返回 false
     */
    public boolean cancel(String instanceId, String reason) {
        PipelineInstance instance = runningInstances.get(instanceId);
        if (instance != null) {
            instance.setCurrentState(PipelineInstance.PipelineState.CANCELLED);
            runningInstances.remove(instanceId);
            log.info("[ORCHESTRATOR] Pipeline 已取消: instanceId={}, reason={}", instanceId, reason);
            return true;
        }
        return false;
    }

    /**
     * 生成实例 ID
     *
     * @return 格式为 "INST-XXXXXXXX" 的唯一实例 ID
     */
    private String generateInstanceId() {
        return "INST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
