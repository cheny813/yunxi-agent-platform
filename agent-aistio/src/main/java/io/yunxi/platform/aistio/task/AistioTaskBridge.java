package io.yunxi.platform.aistio.task;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.extensions.aistio.adapter.AgentTaskStarter;
import io.agentscope.extensions.aistio.adapter.HarnessAgentTaskStarter;
import io.agentscope.extensions.aistio.model.AgentTaskAssignment;

import io.yunxi.platform.shared.codec.CodecSpec;
import reactor.core.publisher.Mono;

/**
 * yunxi 侧的 AgentTask 接活桥 —— 只做 yunxi 专属关照，不重造执行器。
 *
 * <p>分工（按命名主权原则：框架已有的能力不重造，本类只加 yunxi 专属关照）：
 * <ul>
 *   <li><b>框架</b>：{@code SessionBridge.onExecutionAttempt} 收 ASDP 下推 → {@code adapter.handleAgentTask}
 *       → {@code HarnessAgentTaskStarter} 承担「拉任务上下文 → ack → ensureRunning → 跑 Agent →
 *       finish/fail 回传」的完整生命周期（含 attempt 级心跳与 {@code attemptId:generation} 幂等去重）；</li>
 *   <li><b>本类</b>：只加三件 yunxi 独有的事 —— 优雅下线的「停接新活」闸门、correlationId 读回、
 *       接活日志与计数。</li>
 * </ul>
 * 刻意不在此处重复幂等去重：框架 {@code HarnessAgentTaskStarter} 已实现，同一关切只允许一个实现位置。
 *
 * <p>生命周期：应用级单例，构造后可被 {@link #stopAccepting()} 置为不接活（优雅下线第 2 步，
 * 即先停接新活，再等在跑 attempt 完成，最后由连接器关闭连接）。
 */
public class AistioTaskBridge implements AgentTaskStarter {

    private static final Logger log = LoggerFactory.getLogger(AistioTaskBridge.class);
    private static final ObjectMapper MAPPER = CodecSpec.storage();

    private final AgentTaskStarter delegate;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    /** 在跑 attempt 数：优雅下线据此判断是否可以关闭连接 */
    private final AtomicInteger activeAttempts = new AtomicInteger();
    /** 最近接活的 correlation 索引（agentTaskId → correlation），供轨迹/排障查询；仅保留在跑任务 */
    private final Map<String, OrchestrationCorrelation> correlations = new ConcurrentHashMap<>();

    public AistioTaskBridge(AgentTaskStarter delegate) {
        this.delegate = delegate;
    }

    @Override
    public Mono<Void> start(AgentTaskAssignment assignment) {
        if (!accepting.get()) {
            // 优雅下线中不接新活：立即失败回传，避免执行到一半被进程杀死（优雅下线第二步语义）
            String taskId = assignment == null ? "-" : assignment.agentTaskId();
            log.warn("[AISTIO-TASK] 实例正在下线，拒绝新 attempt: task={}", taskId);
            return Mono.error(new IllegalStateException("instance is shutting down, reject new attempt: " + taskId));
        }
        if (assignment == null) {
            return Mono.error(new IllegalArgumentException("AgentTaskAssignment is required"));
        }
        OrchestrationCorrelation correlation = OrchestrationCorrelation.from(assignment);
        correlations.put(assignment.agentTaskId(), correlation);
        activeAttempts.incrementAndGet();
        log.info("[AISTIO-TASK] 接活 task={} run={} node={} attempt={} generation={} correlation={}",
                assignment.agentTaskId(), assignment.runId(), assignment.nodeId(), assignment.attemptId(),
                assignment.generation(), correlation.correlationId());
        return delegate.start(assignment)
                .doFinally(signal -> {
                    correlations.remove(assignment.agentTaskId());
                    activeAttempts.decrementAndGet();
                });
    }

    /**
     * 停止接收新 attempt（优雅下线第 2 步）。已在处理中的 attempt 不受影响，由框架跑完后回传。
     */
    public void stopAccepting() {
        accepting.set(false);
        log.info("[AISTIO-TASK] 已置 accepting=false，停止接收新 attempt");
    }

    public boolean isAccepting() {
        return accepting.get();
    }

    /** 在跑 attempt 数（优雅下线据此判断能否安全关闭连接） */
    public int activeAttemptCount() {
        return activeAttempts.get();
    }

    /**
     * 是否消费 workspace 定义（决定能否声明 {@code workspace-definition-v1} 能力）。
     *
     * <p>为什么不在框架里自动判定：官方 {@code AgentScopeAdapter.capabilities()} 用的是
     * {@code agentTaskStarter instanceof HarnessAgentTaskStarter}，本桥是包装类，instanceof 不成立，
     * 能力将被漏声明 —— 而漏声明的后果是控制面对带 definition 的派发<b>永久 skip 本实例</b>（注册成功却永不接活）。
     * 故由本桥显式透出，装配方据此声明能力。</p>
     */
    public boolean consumesWorkspaceDefinition() {
        return delegate instanceof HarnessAgentTaskStarter starter && starter.consumesWorkspaceDefinition();
    }

    /**
     * 查询在跑任务的 correlation（供排障/轨迹对齐）；任务结束即移除。
     */
    public OrchestrationCorrelation correlationOf(String agentTaskId) {
        return correlations.get(agentTaskId);
    }

    /**
     * 触发—执行相关性（触发侧与执行侧跨协议的关联机制）。
     *
     * <p>触发侧把 {@code _yunxi.correlationId} 写进 webhook body，该 body 原样成为 run input，
     * 再由控制面随 attempt 下推；执行侧在此从 {@code assignment.payload()} 读回。
     * 控制台人工触发没有这一层，此时 {@code degraded=true}（协议允许，仅表示两段无法自动关联）。
     */
    public record OrchestrationCorrelation(
            String correlationId,
            String runId,
            String nodeId,
            String attemptId,
            boolean degraded) {

        private static final String YUNXI_SEGMENT = "_yunxi";
        private static final String CORRELATION_FIELD = "correlationId";

        static OrchestrationCorrelation from(AgentTaskAssignment assignment) {
            String correlationId = null;
            if (assignment.payload() != null && assignment.payload().length > 0) {
                try {
                    JsonNode root = MAPPER.readTree(new String(assignment.payload(), StandardCharsets.UTF_8));
                    JsonNode segment = root.path(YUNXI_SEGMENT);
                    if (!segment.isMissingNode()) {
                        correlationId = segment.path(CORRELATION_FIELD).asText(null);
                    }
                } catch (Exception e) {
                    log.debug("[AISTIO-TASK] attempt payload 非 JSON，无法读取 correlationId: task={}",
                            assignment.agentTaskId());
                }
            }
            boolean degraded = correlationId == null || correlationId.isBlank();
            return new OrchestrationCorrelation(
                    degraded ? null : correlationId,
                    assignment.runId(),
                    assignment.nodeId(),
                    assignment.attemptId(),
                    degraded);
        }
    }
}
