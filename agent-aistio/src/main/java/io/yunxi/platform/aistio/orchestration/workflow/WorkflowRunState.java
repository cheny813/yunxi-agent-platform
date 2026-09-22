package io.yunxi.platform.aistio.orchestration.workflow;

import java.time.Instant;

/**
 * 工作流运行本地状态快照（触发侧 / 执行侧左移写入，仅供 {@link WorkflowTool} 的 status 返回）。
 *
 * <p>权限边界：编排 run 的权威状态在服务端，数据面内网 token 对运行查询 403 无法回读。
 * 本快照只是 yunxi 本地的一份轻量镜像，仅承载两侧各自可用的关联信息，缺失时降级为 unknown，
 * 真相源仍是服务端。</p>
 */
public record WorkflowRunState(
        String correlationId,
        String runId,
        String nodeId,
        String attemptId,
        String phase,
        Instant updatedAt) {
}
