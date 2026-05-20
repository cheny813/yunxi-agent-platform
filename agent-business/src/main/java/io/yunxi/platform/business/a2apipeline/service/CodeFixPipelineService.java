package io.yunxi.platform.business.a2apipeline.service;

import io.yunxi.platform.business.a2apipeline.model.*;
import io.yunxi.platform.business.a2apipeline.config.A2ACodePipelineProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 代码修复流水线服务
 * <p>
 * 对外暴露的 Pipeline 入口服务，委托 PipelineOrchestrator 执行编排。
 * 提供 Pipeline 的启动、状态查询、人工审批和重试能力。
 * </p>
 *
 * <h3>Pipeline Stages</h3>
 * <ol>
 * <li>CODE_ANALYSIS - 代码分析</li>
 * <li>CODE_FIX - 代码修复</li>
 * <li>CODE_REVIEW - 代码审查</li>
 * <li>TEST_EXECUTION - 测试验证</li>
 * </ol>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
public class CodeFixPipelineService {

    /** 流水线编排器 */
    @Autowired
    private PipelineOrchestrator orchestrator;

    /** 流水线配置属性 */
    @Autowired
    private A2ACodePipelineProperties properties;

    /** 历史实例存储（生产环境应使用数据库） */
    private final Map<String, PipelineInstance> instanceStore = new ConcurrentHashMap<>();

    /**
     * 启动流水线
     *
     * @param request 代码修复请求
     * @return Pipeline 实例
     */
    public PipelineInstance startPipeline(CodeFixRequest request) {
        String instanceId = generateInstanceId();
        log.info("启动代码修复流水线: instanceId={}, taskId={}", instanceId, request.getTaskId());

        QualityGateConfig qualityGate = request.getQualityGate() != null
                ? request.getQualityGate()
                : QualityGateConfig.defaultConfig();

        PipelineInstance instance = orchestrator.execute(instanceId, request, qualityGate);
        instanceStore.put(instanceId, instance);

        return instance;
    }

    /**
     * 获取流水线状态
     *
     * @param instanceId 实例 ID
     * @return Pipeline 实例
     */
    public PipelineInstance getPipelineStatus(String instanceId) {
        // 优先从运行中的实例获取
        PipelineInstance running = orchestrator.getRunningInstance(instanceId);
        if (running != null) {
            return running;
        }
        // 从历史存储获取
        return instanceStore.get(instanceId);
    }

    /**
     * 提交人工审核
     * <p>
     * 在 Pipeline 等待审批时，通过此接口提交审批结果
     * </p>
     *
     * @param approval 审批请求
     */
    public void submitManualApproval(ManualApprovalRequest approval) {
        log.info("人工审核提交: instanceId={}, approved={}",
                approval.getInstanceId(), approval.isApproved());

        PipelineInstance instance = instanceStore.get(approval.getInstanceId());
        if (instance == null) {
            log.warn("实例不存在: {}", approval.getInstanceId());
            return;
        }

        if (instance.getCurrentState() != PipelineInstance.PipelineState.WAITING_APPROVAL) {
            log.warn("实例不在等待审批状态: {}", approval.getInstanceId());
            return;
        }

        // 审批通过则继续 Pipeline
        if (approval.isApproved()) {
            instance.setCurrentState(PipelineInstance.PipelineState.RUNNING);
        } else {
            instance.setCurrentState(PipelineInstance.PipelineState.CANCELLED);
        }
    }

    /**
     * 重试流水线
     *
     * @param instanceId 实例 ID
     * @param strategy   重试策略
     */
    public void retryPipeline(String instanceId, RetryStrategy strategy) {
        log.info("重试流水线: instanceId={}, fromStage={}",
                instanceId, strategy.getRetryFromStage());

        PipelineInstance instance = instanceStore.get(instanceId);
        if (instance == null) {
            log.warn("实例不存在: {}", instanceId);
            return;
        }

        // 重新启动 Pipeline
        instance.setCurrentState(PipelineInstance.PipelineState.RUNNING);
    }

    /**
     * 取消流水线
     *
     * @param instanceId 实例 ID
     * @param reason     取消原因
     */
    public void cancelPipeline(String instanceId, String reason) {
        log.info("取消流水线: instanceId={}, reason={}", instanceId, reason);
        orchestrator.cancel(instanceId, reason);

        PipelineInstance instance = instanceStore.get(instanceId);
        if (instance != null) {
            instance.setCurrentState(PipelineInstance.PipelineState.CANCELLED);
        }
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
