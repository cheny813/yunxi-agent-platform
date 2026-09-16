package io.yunxi.platform.trace;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.agentscope.core.message.ToolResultState;

/**
 * 推理轨迹节点快照。
 *
 * <p>轨迹的中间表示。同一节点的 {@link #stableKey()} 会先后出现 {@link Snapshot#OPEN}、
 * {@link Snapshot#DELTA}、{@link Snapshot#CLOSED} 三种快照，消费方按稳定标识分组即可还原
 * 完整节点：开始开窗、增量透传、关闭关窗。</p>
 *
 * <p>字段分四组：归属（定位节点）、树形（还原结构）、快照（表达流式过程）、终态（关闭时填充）。
 * 树形关系不依赖框架事件的来源字段直接充当父指针——来源字段表达的是来源代理路径，与
 * 父子关系不同，故此处以 {@code agentPath} 与 {@code depth} 分开承载。</p>
 *
 * @param traceId   轨迹标识，一个用户回合一个值
 * @param stableKey 节点稳定标识，同一节点的三种快照共享此值
 * @param eventId   来源事件标识，用于审计与排序，不参与节点关联
 * @param kind      节点种类
 * @param agentPath 来源代理路径，顶层为 null
 * @param depth     来源代理路径的层级
 * @param parentKey 父节点的稳定标识，根节点为 null
 * @param phase     执行阶段，业务可选，以字符串承载以免与阶段枚举耦合
 * @param snapshot  快照阶段
 * @param delta     增量内容片段，仅 {@link Snapshot#DELTA} 快照携带
 * @param status    终态，关闭快照填充，取值复用框架的工具结果状态
 * @param durationMs 持续时长（毫秒），关闭快照填充
 * @param payload   种类相关负载：用量、参数、结果、任务、意图等
 * @param at        快照产生时间（毫秒）
 * @author yunxi-agent-platform
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReasoningSpan(
        String traceId,
        String stableKey,
        String eventId,
        SpanKind kind,
        String agentPath,
        int depth,
        String parentKey,
        String phase,
        Snapshot snapshot,
        String delta,
        ToolResultState status,
        Long durationMs,
        Map<String, Object> payload,
        long at) {

    /**
     * 构造开始快照。
     *
     * @param traceId   轨迹标识
     * @param stableKey 节点稳定标识
     * @param eventId   来源事件标识
     * @param kind      节点种类
     * @param parentKey 父节点稳定标识
     * @return 开始快照
     */
    public static ReasoningSpan open(String traceId, String stableKey, String eventId,
                                    SpanKind kind, String parentKey) {
        return new ReasoningSpan(traceId, stableKey, eventId, kind, null, 0, parentKey,
                null, Snapshot.OPEN, null, null, null, null, System.currentTimeMillis());
    }

    /**
     * 构造增量快照。
     *
     * @param traceId   轨迹标识
     * @param stableKey 节点稳定标识
     * @param eventId   来源事件标识
     * @param kind      节点种类
     * @param delta     增量内容片段
     * @return 增量快照
     */
    public static ReasoningSpan delta(String traceId, String stableKey, String eventId,
                                     SpanKind kind, String delta) {
        return new ReasoningSpan(traceId, stableKey, eventId, kind, null, 0, null,
                null, Snapshot.DELTA, delta, null, null, null, System.currentTimeMillis());
    }

    /**
     * 构造关闭快照。
     *
     * @param traceId    轨迹标识
     * @param stableKey  节点稳定标识
     * @param eventId    来源事件标识
     * @param kind       节点种类
     * @param status     终态
     * @param durationMs 持续时长（毫秒）
     * @param payload    种类相关负载
     * @return 关闭快照
     */
    public static ReasoningSpan closed(String traceId, String stableKey, String eventId,
                                       SpanKind kind, ToolResultState status, Long durationMs,
                                       Map<String, Object> payload) {
        return new ReasoningSpan(traceId, stableKey, eventId, kind, null, 0, null,
                null, Snapshot.CLOSED, null, status, durationMs, payload,
                System.currentTimeMillis());
    }

    /**
     * 附加执行阶段，返回新快照。
     *
     * @param value 阶段名称
     * @return 附加阶段后的快照
     */
    public ReasoningSpan withPhase(String value) {
        return new ReasoningSpan(traceId, stableKey, eventId, kind, agentPath, depth, parentKey,
                value, snapshot, delta, status, durationMs, payload, at);
    }

    /**
     * 附加来源代理路径，返回新快照。层级由路径的分隔符数量推导。
     *
     * @param path 来源代理路径，顶层传 null
     * @return 附加路径后的快照
     */
    public ReasoningSpan withAgentPath(String path) {
        int level = 0;
        if (path != null && !path.isBlank()) {
            for (int i = 0; i < path.length(); i++) {
                if (path.charAt(i) == '/') {
                    level++;
                }
            }
            level++;
        }
        return new ReasoningSpan(traceId, stableKey, eventId, kind, path, level, parentKey,
                phase, snapshot, delta, status, durationMs, payload, at);
    }

    /**
     * 附加负载，返回新快照。
     *
     * @param values 种类相关负载
     * @return 附加负载后的快照
     */
    public ReasoningSpan withPayload(Map<String, Object> values) {
        return new ReasoningSpan(traceId, stableKey, eventId, kind, agentPath, depth, parentKey,
                phase, snapshot, delta, status, durationMs, values, at);
    }

    /**
     * 是否为开始快照。
     *
     * @return 是开始快照返回 true
     */
    @JsonIgnore
    public boolean isOpen() {
        return snapshot == Snapshot.OPEN;
    }

    /**
     * 是否为增量快照。
     *
     * @return 是增量快照返回 true
     */
    @JsonIgnore
    public boolean isDelta() {
        return snapshot == Snapshot.DELTA;
    }

    /**
     * 是否为关闭快照。
     *
     * @return 是关闭快照返回 true
     */
    @JsonIgnore
    public boolean isClosed() {
        return snapshot == Snapshot.CLOSED;
    }
}
