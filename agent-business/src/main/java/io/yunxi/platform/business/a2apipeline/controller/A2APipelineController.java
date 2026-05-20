package io.yunxi.platform.business.a2apipeline.controller;

import io.yunxi.platform.business.a2apipeline.model.*;
import io.yunxi.platform.business.a2apipeline.service.CodeFixPipelineService;
import io.yunxi.platform.business.a2apipeline.service.PipelineOrchestrator;
import io.yunxi.platform.shared.dto.CommonResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * A2A Pipeline API 控制器
 * <p>
 * 提供代码修复流水线的 REST API 接口：
 * </p>
 * <ul>
 * <li>POST /api/a2a-pipeline/start - 启动 Pipeline</li>
 * <li>GET /api/a2a-pipeline/status/{instanceId} - 查询状态</li>
 * <li>POST /api/a2a-pipeline/approval - 人工审批</li>
 * <li>POST /api/a2a-pipeline/retry/{instanceId} - 重试</li>
 * <li>DELETE /api/a2a-pipeline/cancel/{instanceId} - 取消</li>
 * </ul>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/api/a2a-pipeline")
public class A2APipelineController {

    /** 代码修复流水线服务 */
    @Autowired
    private CodeFixPipelineService pipelineService;

    /** 流水线编排器 */
    @Autowired
    private PipelineOrchestrator orchestrator;

    /**
     * 启动代码修复流水线
     *
     * @param request 代码修复请求
     * @return Pipeline 实例
     */
    @PostMapping("/start")
    public CommonResponse<PipelineInstance> startPipeline(@RequestBody CodeFixRequest request) {
        try {
            if (request.getTitle() == null || request.getTitle().isBlank()) {
                return CommonResponse.error("任务标题不能为空");
            }
            if (request.getDescription() == null || request.getDescription().isBlank()) {
                return CommonResponse.error("问题描述不能为空");
            }

            PipelineInstance instance = pipelineService.startPipeline(request);
            return CommonResponse.success(instance);

        } catch (Exception e) {
            log.error("启动流水线失败", e);
            return CommonResponse.error("启动流水线失败: " + e.getMessage());
        }
    }

    /**
     * 查询流水线状态
     *
     * @param instanceId 实例 ID
     * @return Pipeline 实例
     */
    @GetMapping("/status/{instanceId}")
    public CommonResponse<PipelineInstance> getStatus(@PathVariable String instanceId) {
        try {
            PipelineInstance instance = pipelineService.getPipelineStatus(instanceId);
            if (instance == null) {
                return CommonResponse.error("实例不存在: " + instanceId);
            }
            return CommonResponse.success(instance);

        } catch (Exception e) {
            log.error("查询状态失败", e);
            return CommonResponse.error("查询状态失败: " + e.getMessage());
        }
    }

    /**
     * 提交人工审批
     *
     * @param approval 审批请求
     * @return 操作结果
     */
    @PostMapping("/approval")
    public CommonResponse<Void> submitApproval(@RequestBody ManualApprovalRequest approval) {
        try {
            pipelineService.submitManualApproval(approval);
            return CommonResponse.success(null);

        } catch (Exception e) {
            log.error("提交审批失败", e);
            return CommonResponse.error("提交审批失败: " + e.getMessage());
        }
    }

    /**
     * 重试流水线
     *
     * @param instanceId 实例 ID
     * @param strategy   重试策略
     * @return 操作结果
     */
    @PostMapping("/retry/{instanceId}")
    public CommonResponse<Void> retryPipeline(
            @PathVariable String instanceId,
            @RequestBody(required = false) RetryStrategy strategy) {
        try {
            RetryStrategy retryStrategy = strategy != null ? strategy : RetryStrategy.defaultStrategy();
            pipelineService.retryPipeline(instanceId, retryStrategy);
            return CommonResponse.success(null);

        } catch (Exception e) {
            log.error("重试流水线失败", e);
            return CommonResponse.error("重试失败: " + e.getMessage());
        }
    }

    /**
     * 取消流水线
     *
     * @param instanceId 实例 ID
     * @param reason     取消原因
     * @return 操作结果
     */
    @DeleteMapping("/cancel/{instanceId}")
    public CommonResponse<Void> cancelPipeline(
            @PathVariable String instanceId,
            @RequestParam(required = false) String reason) {
        try {
            pipelineService.cancelPipeline(instanceId, reason != null ? reason : "用户取消");
            return CommonResponse.success(null);

        } catch (Exception e) {
            log.error("取消流水线失败", e);
            return CommonResponse.error("取消失败: " + e.getMessage());
        }
    }
}
