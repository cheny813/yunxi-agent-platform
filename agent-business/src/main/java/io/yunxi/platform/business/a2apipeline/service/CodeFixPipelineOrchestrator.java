package io.yunxi.platform.business.a2apipeline.service;

import io.yunxi.platform.business.a2apipeline.model.*;
import io.yunxi.platform.shared.dto.StageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 代码修复流水线编排器
 * <p>
 * 基于 List&lt;PipelineStage&gt; 驱动，每个阶段需通过门控检查：
 * <ul>
 * <li>canExecute: 前置条件满足才执行，否则跳过</li>
 * <li>validateResult: 后置条件验证，确认结果有效</li>
 * </ul>
 * 未实现的阶段（canExecute=false）会被跳过并记录警告。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
public class CodeFixPipelineOrchestrator {

    /** 注册的流水线阶段列表 */
    private final List<PipelineStage> stages;

    /**
     * 构造编排器，注入所有 PipelineStage 实现
     *
     * @param stages 流水线阶段列表
     */
    @Autowired
    public CodeFixPipelineOrchestrator(List<PipelineStage> stages) {
        this.stages = stages != null ? stages : List.of();
        log.info("[Orchestrator] 注册 {} 个流水线阶段: {}", this.stages.size(),
                this.stages.stream().map(PipelineStage::getName).toList());
    }

    /**
     * 编排代码修复流水线，顺序执行所有注册阶段
     *
     * @param request 代码修复请求
     * @return 流水线执行结果
     */
    public PipelineResult orchestrate(CodeFixRequest request) {
        String instanceId = generateInstanceId();
        log.info("[Orchestrator] Starting pipeline: instanceId={}, taskId={}", instanceId, request.getTaskId());

        PipelineContext context = PipelineContext.create(instanceId, request.getTaskId(), request);
        Map<String, StageResult> completedResults = new LinkedHashMap<>();

        try {
            for (PipelineStage stage : stages) {
                String stageName = stage.getName();
                context.setCurrentStage(stageName);

                // 门控：前置条件检查
                if (!stage.canExecute(completedResults)) {
                    log.warn("[Orchestrator] 阶段 '{}' 跳过: 前置条件不满足", stageName);
                    completedResults.put(stageName, StageResult.builder()
                            .stageName(stageName)
                            .status(StageResult.StageStatus.SKIPPED)
                            .startTimeMs(System.currentTimeMillis())
                            .endTimeMs(System.currentTimeMillis())
                            .durationMs(0)
                            .output(Map.of("reason", "前置条件不满足"))
                            .build());
                    continue;
                }

                // 执行阶段
                log.info("[Orchestrator] 执行阶段: {}", stageName);
                StageResult result = stage.execute(context, completedResults);

                // 门控：后置条件验证
                if (!stage.validateResult(result)) {
                    log.error("[Orchestrator] 阶段 '{}' 结果验证失败", stageName);
                    return PipelineResult.failure(instanceId, request.getTaskId(),
                            "阶段 " + stageName + " 结果验证失败");
                }

                completedResults.put(stageName, result);

                // 阶段失败则终止流水线
                if (result.getStatus() == StageResult.StageStatus.FAILED) {
                    log.warn("[Orchestrator] 阶段 '{}' 执行失败，流水线终止", stageName);
                    return PipelineResult.failure(instanceId, request.getTaskId(),
                            "阶段 " + stageName + " 执行失败");
                }
            }

            return PipelineResult.success(instanceId, request.getTaskId());

        } catch (UnsupportedOperationException e) {
            log.error("[Orchestrator] 阶段 '{}' 未实现: {}", context.getCurrentStage(), e.getMessage());
            return PipelineResult.failure(instanceId, request.getTaskId(),
                    "阶段 " + context.getCurrentStage() + " 未实现: " + e.getMessage());
        } catch (Exception e) {
            log.error("[Orchestrator] Pipeline failed: instanceId={}", instanceId, e);
            return PipelineResult.failure(instanceId, request.getTaskId(), e.getMessage());
        }
    }

    /**
     * 生成实例 ID
     *
     * @return 格式为 "ORCH-UUID" 的唯一实例 ID
     */
    private String generateInstanceId() {
        return "ORCH-" + UUID.randomUUID().toString();
    }
}
