package io.yunxi.platform.aistio.orchestration.workflow;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作流运行本地状态表（触发侧读、触发侧 / 执行侧写）。
 *
 * <p>权限边界：编排 run 的权威状态在服务端，数据面内网 token 对运行查询 403 无法回读。
 * 本表是 yunxi 本地的一份轻量状态镜像，仅承载两侧各自可左移写入的关联信息（correlationId → runId），
 * 供触发侧 {@link WorkflowTool#status} 返回；缺失时降级为 unknown。真相源仍是服务端，本地表仅作状态镜像。</p>
 *
 * <p>phase 取值：{@code triggered}（触发侧 start 成功后写入，触发侧视角） /
 * {@code accepted}（执行侧接活时写入，执行侧视角，属后续运行可观测范围） /
 * {@code cancellation_requested}（触发侧标记取消意图）。</p>
 */
public interface WorkflowRunStateStore {

    /** 触发侧 start 成功后写入：建立 correlationId → automationRunId 关联（触发侧视角） */
    void markTriggered(String correlationId, String runId, String definitionKey, String bizRequestId);

    /** 执行侧接活时写入：建立 correlationId → runId（执行侧视角，属后续运行可观测范围） */
    void markAccepted(String correlationId, String runId, String nodeId, String attemptId);

    /** 读取本地状态；无记录返回 null（触发侧降级为 unknown） */
    WorkflowRunState get(String correlationId);

    /** 触发侧 / 用户标记取消意图（执行侧是否据此中断属后续运行可观测范围） */
    void markCancellationRequested(String correlationId);

    /** 进程内内存实现（默认；重启即失效，适用于单实例与非跨重启场景） */
    class InMemory implements WorkflowRunStateStore {
        private final Map<String, WorkflowRunState> store = new ConcurrentHashMap<>();

        @Override
        public void markTriggered(String correlationId, String runId, String definitionKey, String bizRequestId) {
            store.put(correlationId, new WorkflowRunState(correlationId, runId, definitionKey, bizRequestId, "triggered", Instant.now()));
        }

        @Override
        public void markAccepted(String correlationId, String runId, String nodeId, String attemptId) {
            store.put(correlationId, new WorkflowRunState(correlationId, runId, nodeId, attemptId, "accepted", Instant.now()));
        }

        @Override
        public WorkflowRunState get(String correlationId) {
            return store.get(correlationId);
        }

        @Override
        public void markCancellationRequested(String correlationId) {
            WorkflowRunState s = store.get(correlationId);
            String runId = s == null ? null : s.runId();
            String nodeId = s == null ? null : s.nodeId();
            String attemptId = s == null ? null : s.attemptId();
            store.put(correlationId, new WorkflowRunState(correlationId, runId, nodeId, attemptId, "cancellation_requested", Instant.now()));
        }
    }
}
