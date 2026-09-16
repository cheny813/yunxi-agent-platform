package io.yunxi.platform.trace;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.DataBlockDeltaEvent;
import io.agentscope.core.event.DataBlockEndEvent;
import io.agentscope.core.event.DataBlockStartEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ExternalExecutionResultEvent;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import reactor.core.publisher.Flux;

/**
 * 轨迹归集器。
 *
 * <p>把框架的细粒度事件流归集为轨迹节点快照流。归集做三件事：按事件类型确定节点种类与
 * 快照阶段；按配对键推导节点稳定标识，使同一节点的多次快照可被分组还原；按事件流的
 * 嵌套关系建立节点树并标记父子关系。</p>
 *
 * <p>归集是纯函数式的：只读事件、不改变事件内容，输出为独立的快照流，可被多个消费方
 * 各自订阅而不互相影响。父子关系由事件流的进入与退出推导：节点在开始事件到来时压栈、
 * 在配对事件到来时出栈，父节点即压栈那一刻的最内层节点。来源字段不参与这个推导，但
 * 参与节点标识的定位 —— 转发上来的子代理事件可能与父回合携带相同的配对键值，不连同
 * 来源代理路径一起定位就会退化成同一个节点（详见 {@code ComposerState#stableKey}）。</p>
 *
 * <p>未决标记：回合结束时仍有未关闭的人机交互节点，其终态记为未决，表示请求已发出但
 * 尚未被处理。这一情形既非成功也非失败，单独表达。</p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class TraceComposer {

    private static final Logger log = LoggerFactory.getLogger(TraceComposer.class);

    /** 节点稳定标识的分隔符 */
    private static final char KEY_SEPARATOR = '|';

    /**
     * 归集一次运行的事件流。
     *
     * @param events  框架事件流
     * @param traceId 轨迹标识
     * @return 轨迹节点快照流
     */
    public Flux<ReasoningSpan> compose(Flux<AgentEvent> events, String traceId) {
        ComposerState state = new ComposerState(traceId);
        return events.concatMap(event -> Flux.fromIterable(state.accept(event)))
                .concatWith(Flux.defer(() -> Flux.fromIterable(state.close())));
    }

    /**
     * 开启一次有状态的归集会话。
     *
     * <p>用于归集发生在事件流<b>之外</b>的场景：调用方在流外逐个投喂事件，需要归集状态
     * 跨事件存活。若改为对每个事件单独调用 {@link #compose}，每次都会新建归集状态，
     * 于是配对键无从匹配、节点栈恒为空、未决标记永远算不出来 —— 输出看似是一串合法快照，
     * 实际已失去全部结构与配对信息。此入口存在的意义就是把状态生命周期交还给调用方。</p>
     *
     * <p>会话不是线程安全的，且一次运行应当独占一个会话（对应一次调用、一棵轨迹树）。</p>
     *
     * @param traceId 轨迹标识
     * @return 归集会话
     */
    public Session session(String traceId) {
        return new Session(traceId);
    }

    /**
     * 有状态归集会话：跨事件保持归集状态，供流外逐事件投喂。
     */
    public static final class Session {

        private final ComposerState state;

        private Session(String traceId) {
            this.state = new ComposerState(traceId);
        }

        /**
         * 投喂一个事件，返回其产生的快照。
         *
         * @param event 事件
         * @return 快照列表；该事件不产生快照时为空列表
         */
        public java.util.List<ReasoningSpan> accept(AgentEvent event) {
            return state.accept(event);
        }

        /**
         * 结束会话，为仍处于打开状态的节点补关闭快照。
         *
         * @return 收尾快照列表
         */
        public java.util.List<ReasoningSpan> close() {
            return state.close();
        }

        /**
         * 注入一个由编排层产生、事件流里不存在的节点。
         *
         * <p>存在的理由：有一类语义段由 yunxi 编排层在**发起调用之前**产出，AgentScope-Java
         * 的运行时完全不知道它的存在，事件流里自然也没有对应事件。意图分析是典型 ——
         * 它在请求解析阶段完成，产出改道决策与实体改写，但这棵树是「为什么走到这个子流程」
         * 的唯一答案来源。若不能入树，该问题就永远答不出来。</p>
         *
         * <p><b>为什么必须由会话注入而非独立构造快照</b>：注入的节点要与树上已有节点建立
         * 父子关系，而这依赖归集状态里的节点栈。在会话外自行拼一个快照，得到的是一个没有
         * 归属的孤立节点 —— 树形结构对它没有意义。故此入口挂在会话上，由会话决定它挂在谁下面。</p>
         *
         * <p>注入是<b>一次性完整节点</b>（开启与关闭同时产生），因为编排层节点在注入时已
         * 有确定结论，不像流式节点那样需要分阶段推进。</p>
         *
         * @param kind    节点种类（如 {@link SpanKind#INTENT}）
         * @param payload 节点负载，作为两个快照共用的内容
         * @param durationMs 节点耗时（毫秒）
         * @return 该节点产生的快照列表（开启 + 关闭）
         */
        public java.util.List<ReasoningSpan> inject(SpanKind kind, Map<String, Object> payload,
                                                    long durationMs) {
            return state.inject(kind, payload, durationMs);
        }
    }

    /**
     * 归集状态：随流推进而演进，不跨运行共享。
     *
     * <p>每个事件流使用独立实例，因此无需加锁 —— 归集发生在订阅者的调用线程上。</p>
     */
    private static final class ComposerState {

        private final String traceId;
        /** 已打开节点的栈，栈底为回合节点 */
        private final Deque<OpenNode> stack = new ArrayDeque<>();
        /** 每个节点的开始时间，用于关闭时计算时长 */
        private final Map<String, Long> startedAt = new LinkedHashMap<>();
        /** 已发出的稳定标识，用于判定增量的父子归属 */
        private final Map<SpanKind, String> currentByKind = new LinkedHashMap<>();
        /** 未决的人机交互节点计数 */
        private int pendingHitl;
        /** 编排层注入节点的序号，按种类分别计数，保证同一运行内标识唯一 */
        private final Map<SpanKind, Integer> injectedSeq = new LinkedHashMap<>();

        ComposerState(String traceId) {
            this.traceId = traceId;
        }

        /** 意图工具名：模型侧意图分类工具，其调用归类为 INTENT 节点（见 {@link #accept}）。 */
        private static final String INTENT_TOOL_NAME = "intent_classify";

        /** 本回合内已识别为意图工具的 toolCallId 集合，用于把其全部事件归为 INTENT 节点。 */
        private final Set<String> intentToolCallIds = new HashSet<>();

        private static final Set<AgentEventType> TOOL_CALL_EVENTS = Set.of(
                AgentEventType.TOOL_CALL_START,
                AgentEventType.TOOL_CALL_DELTA,
                AgentEventType.TOOL_CALL_END,
                AgentEventType.TOOL_RESULT_START,
                AgentEventType.TOOL_RESULT_TEXT_DELTA,
                AgentEventType.TOOL_RESULT_DATA_DELTA,
                AgentEventType.TOOL_RESULT_END);

        /**
         * 把意图工具（intent_classify）调用的全部事件重分类为 INTENT 节点。
         * 仅在首个 TOOL_CALL_START 按工具名识别并登记 toolCallId；其后同 toolCallId 的
         * CALL / DELTA / END / RESULT 事件一并归为 INTENT，使该节点所有快照的 kind 一致且携带 toolCallId
         * （满足验收项②：INTENT span 与其所属工具的 toolCallId 可配对）。
         */
        private EventSpanMapping.Rule reclassifyIntentTool(AgentEvent event, AgentEventType type,
                EventSpanMapping.Rule rule) {
            if (rule.kind() != SpanKind.TOOL_CALL || !TOOL_CALL_EVENTS.contains(type)) {
                return rule;
            }
            String toolCallId = toolCallIdOf(event);
            if (event instanceof ToolCallStartEvent s && INTENT_TOOL_NAME.equals(s.getToolCallName())) {
                if (toolCallId != null) {
                    intentToolCallIds.add(toolCallId);
                }
                return new EventSpanMapping.Rule(SpanKind.INTENT, rule.snapshot(), rule.pairKey());
            }
            if (toolCallId != null && intentToolCallIds.contains(toolCallId)) {
                return new EventSpanMapping.Rule(SpanKind.INTENT, rule.snapshot(), rule.pairKey());
            }
            return rule;
        }

        /**
         * 处理单个事件，返回其产生的快照列表。
         */
        java.util.List<ReasoningSpan> accept(AgentEvent event) {
            if (event == null) {
                return java.util.List.of();
            }
            AgentEventType type = event.getType();
            EventSpanMapping.Rule rule = EventSpanMapping.ruleOf(type);
            if (rule == null) {
                log.debug("未登记的事件类型，忽略: {}", type);
                return java.util.List.of();
            }
            // 意图工具节点重分类：模型侧 intent_classify 工具调用归类为 INTENT 节点并携带 toolCallId，
            // 满足验收项②（INTENT span 与其所属工具的 toolCallId 可配对）。该工具以 TOOL_CALL 形式进入事件流，
            // 本处把其种类从 TOOL_CALL 重写为 INTENT；配对键仍为 TOOL_CALL_ID，故天然带 toolCallId，
            // 且 CALL/DELTA/END/RESULT 事件共享同一 toolCallId → 归属同一节点（kind 由本开启事件确定）。
            rule = reclassifyIntentTool(event, type, rule);
            try {
                return switch (rule.snapshot()) {
                    case OPEN -> onOpen(event, type, rule);
                    case DELTA -> onDelta(event, type, rule);
                    case CLOSED -> onClosed(event, type, rule);
                };
            } catch (Exception e) {
                log.warn("事件归集失败: type={}, traceId={}: {}", type, traceId, e.getMessage());
                return java.util.List.of();
            }
        }

        private java.util.List<ReasoningSpan> onOpen(AgentEvent event, AgentEventType type,
                                                    EventSpanMapping.Rule rule) {
            String key = stableKey(event, type, rule);
            String parentKey = stack.isEmpty() ? null : stack.peek().key;
            long now = System.currentTimeMillis();
            startedAt.put(key, now);
            stack.push(new OpenNode(key, rule.kind()));
            currentByKind.put(rule.kind(), key);

            ReasoningSpan span = ReasoningSpan
                    .open(traceId, key, event.getId(), rule.kind(), parentKey)
                    .withAgentPath(event.getSource())
                    .withPayload(payloadOf(event))
                    .withPhase(phaseOf(rule.kind()));
            if (rule.kind() == SpanKind.HITL) {
                pendingHitl++;
            }
            return java.util.List.of(span);
        }

        private java.util.List<ReasoningSpan> onDelta(AgentEvent event, AgentEventType type,
                                                     EventSpanMapping.Rule rule) {
            String delta = deltaOf(event);
            if (delta == null) {
                return java.util.List.of();
            }
            String key = stableKeyForDelta(event, type, rule);
            if (key == null) {
                // 孤儿增量：找不到归属节点，丢弃而不猜测
                log.debug("孤儿增量事件，已忽略: type={}, traceId={}", type, traceId);
                return java.util.List.of();
            }
            ReasoningSpan span = ReasoningSpan
                    .delta(traceId, key, event.getId(), rule.kind(), delta)
                    .withAgentPath(event.getSource());
            return java.util.List.of(span);
        }

        private java.util.List<ReasoningSpan> onClosed(AgentEvent event, AgentEventType type,
                                                      EventSpanMapping.Rule rule) {
            String key = stableKey(event, type, rule);
            java.util.List<ReasoningSpan> result = new java.util.ArrayList<>(1);
            Long start = startedAt.remove(key);
            Long duration = start == null ? null : System.currentTimeMillis() - start;

            Map<String, Object> payload = payloadOf(event);
            ReasoningSpan span = ReasoningSpan
                    .closed(traceId, key, event.getId(), rule.kind(), statusOf(event, rule.kind()),
                            duration, payload)
                    .withAgentPath(event.getSource());
            result.add(span);

            if (rule.kind() == SpanKind.HITL) {
                pendingHitl = Math.max(0, pendingHitl - 1);
            }
            closeNode(key);

            // 回合结束时，为其下未关闭的人机交互节点补未决终态
            if (type == AgentEventType.AGENT_END) {
                result.addAll(drainPendingHitl());
            }
            return result;
        }

        /**
         * 注入编排层产生的完整节点（开启 + 关闭同时产出）。
         *
         * <p>与 {@code onOpen} / {@code onClosed} 共用父子推导规则：父节点取注入那一刻
         * 栈顶的节点。这意味着<b>注入时机决定归属</b> —— 在回合开始前注入会成为根的子节点，
         * 在某个工具调用期间注入则挂在该工具之下。调用方应按语义选择时机。</p>
         *
         * <p>节点不入栈：它是完整节点，不需要等待配对事件出栈。若入栈会造成无人出栈的
         * 悬挂状态，使后续所有节点的父子关系都错挂在它下面。</p>
         *
         * <p>标识由种类与递增序号组成，不依赖任何框架字段 —— 编排层节点不由框架事件产生，
         * 没有 replyId / blockId / toolCallId 可用。</p>
         */
        java.util.List<ReasoningSpan> inject(SpanKind kind, Map<String, Object> payload,
                                             long durationMs) {
            if (kind == null) {
                return java.util.List.of();
            }
            try {
                String key = injectedKey(kind);
                String parentKey = stack.isEmpty() ? null : stack.peek().key;
                Map<String, Object> safePayload = payload == null ? Map.of() : payload;

                ReasoningSpan open = ReasoningSpan
                        .open(traceId, key, null, kind, parentKey)
                        .withPayload(safePayload)
                        .withPhase(phaseOf(kind));
                ReasoningSpan closed = ReasoningSpan
                        .closed(traceId, key, null, kind,
                                io.agentscope.core.message.ToolResultState.SUCCESS,
                                durationMs, safePayload)
                        .withPhase(phaseOf(kind));
                return java.util.List.of(open, closed);
            } catch (Exception e) {
                log.warn("节点注入失败: kind={}, traceId={}: {}", kind, traceId, e.getMessage());
                return java.util.List.of();
            }
        }

        /** 编排层节点的标识：种类 + 该种类在本次运行内的递增序号。 */
        private String injectedKey(SpanKind kind) {
            int seq = injectedSeq.merge(kind, 1, Integer::sum);
            return kind.name() + KEY_SEPARATOR + "injected" + KEY_SEPARATOR + seq;
        }

        /**
         * 回合结束时，把仍未关闭的人机交互节点标记为未决。
         */
        private java.util.List<ReasoningSpan> drainPendingHitl() {
            if (pendingHitl <= 0) {
                return java.util.List.of();
            }
            java.util.List<ReasoningSpan> result = new java.util.ArrayList<>();
            java.util.List<OpenNode> remaining = new java.util.ArrayList<>(stack);
            for (OpenNode node : remaining) {
                if (node.kind == SpanKind.HITL) {
                    Long start = startedAt.remove(node.key);
                    Long duration = start == null ? null : System.currentTimeMillis() - start;
                    result.add(ReasoningSpan.closed(traceId, node.key, null, SpanKind.HITL,
                                    SpanOutcome.PENDING, duration, Map.of("pending", true))
                            .withPhase(phaseOf(SpanKind.HITL)));
                }
            }
            pendingHitl = 0;
            return result;
        }

        /**
         * 流结束：为仍处于打开状态的节点补关闭快照，避免出现无终点的节点。
         */
        java.util.List<ReasoningSpan> close() {
            java.util.List<ReasoningSpan> result = new java.util.ArrayList<>();
            while (!stack.isEmpty()) {
                OpenNode node = stack.pop();
                Long start = startedAt.remove(node.key);
                Long duration = start == null ? null : System.currentTimeMillis() - start;
                io.agentscope.core.message.ToolResultState status =
                        node.kind == SpanKind.HITL && pendingHitl > 0
                                ? SpanOutcome.PENDING
                                : SpanOutcome.closingOutcome(0, false);
                result.add(ReasoningSpan.closed(traceId, node.key, null, node.kind,
                                status, duration, Map.of("autoClosed", true))
                        .withPhase(phaseOf(node.kind)));
            }
            pendingHitl = 0;
            currentByKind.clear();
            return result;
        }

        private void closeNode(String key) {
            stack.removeIf(node -> node.key.equals(key));
            currentByKind.values().removeIf(key::equals);
        }

        /**
         * 推导节点稳定标识。
         *
         * <p>标识只由配对键的值决定，不掺入事件类型 —— 同一逻辑节点的开始、增量、关闭由
         * 不同事件类型表达，掺入类型会使三种快照无法归到同一节点。</p>
         *
         * <p>标识另含三段限定把取值定位到唯一节点：来源代理路径、节点种类、配对键类别。
         * 来源代理路径标明事件的发出者，使转发上来的子代理事件即便与父回合携带相同的
         * 回复标识也不会落到同一节点；节点种类区分配对键相同的多种节点 —— 回合节点与
         * 模型调用节点在一次回复里携带相同的回复标识，人机交互节点同理；配对键类别使
         * 不同类别的业务标识偶然相同时不互相覆盖。三者缺一，本该并列的节点会被折叠成
         * 一个。</p>
         *
         * <p>来源代理路径在同属一个节点的全部事件上一致，因此不会把同一节点切成两个：
         * AgentScope-Java 的转发实现是整条子流统一打标，而非逐事件挑选。</p>
         */
        private String stableKey(AgentEvent event, AgentEventType type,
                                 EventSpanMapping.Rule rule) {
            String source = event.getSource();
            return switch (rule.pairKey()) {
                case REPLY_ID -> buildKey(rule.kind(), source, "r", replyIdOf(event));
                case BLOCK_ID -> buildKey(rule.kind(), source, "b", blockIdOf(event));
                case TOOL_CALL_ID -> buildKey(rule.kind(), source, "t", toolCallIdOf(event));
                case NONE -> buildKey(rule.kind(), source, "e", event.getId());
            };
        }

        /**
         * 增量的稳定标识：优先用配对键；无配对键时沿用当前同类节点的标识。
         */
        private String stableKeyForDelta(AgentEvent event, AgentEventType type,
                                         EventSpanMapping.Rule rule) {
            String key = stableKey(event, type, rule);
            if (rule.pairKey() != EventSpanMapping.PairKey.NONE) {
                return startedAt.containsKey(key) ? key : null;
            }
            return currentByKind.get(rule.kind());
        }

        private static String buildKey(SpanKind kind, String source, String pairPrefix,
                                       String value) {
            String id = value == null ? "unknown" : value;
            String path = source == null || source.isEmpty() ? "-" : source;
            return kind.name() + KEY_SEPARATOR + path + KEY_SEPARATOR + pairPrefix
                    + KEY_SEPARATOR + id;
        }

        /** 节点种类对应的执行阶段 */
        private static String phaseOf(SpanKind kind) {
            return switch (kind) {
                case MODEL_CALL, REASONING -> "THINKING";
                case TOOL_CALL -> "TOOL_CALL";
                case TEXT, DATA -> "ANSWER";
                default -> null;
            };
        }

        private record OpenNode(String key, SpanKind kind) {
        }

        // ── 事件字段读取：集中在此，避免归集逻辑散落字段判断 ──

        private static String replyIdOf(AgentEvent event) {
            if (event instanceof AgentStartEvent e) {
                return e.getReplyId();
            }
            if (event instanceof AgentEndEvent e) {
                return e.getReplyId();
            }
            if (event instanceof ModelCallStartEvent e) {
                return e.getReplyId();
            }
            if (event instanceof ModelCallEndEvent e) {
                return e.getReplyId();
            }
            if (event instanceof TextBlockStartEvent e) {
                return e.getReplyId();
            }
            if (event instanceof TextBlockEndEvent e) {
                return e.getReplyId();
            }
            if (event instanceof ThinkingBlockStartEvent e) {
                return e.getReplyId();
            }
            if (event instanceof ThinkingBlockEndEvent e) {
                return e.getReplyId();
            }
            if (event instanceof DataBlockStartEvent e) {
                return e.getReplyId();
            }
            if (event instanceof DataBlockEndEvent e) {
                return e.getReplyId();
            }
            if (event instanceof ToolCallStartEvent e) {
                return e.getReplyId();
            }
            if (event instanceof ToolCallEndEvent e) {
                return e.getReplyId();
            }
            if (event instanceof RequireUserConfirmEvent e) {
                return e.getReplyId();
            }
            if (event instanceof UserConfirmResultEvent e) {
                return e.getReplyId();
            }
            if (event instanceof RequireExternalExecutionEvent e) {
                return e.getReplyId();
            }
            if (event instanceof ExternalExecutionResultEvent e) {
                return e.getReplyId();
            }
            if (event instanceof ExceedMaxItersEvent e) {
                return e.getReplyId();
            }
            if (event instanceof HintBlockEvent e) {
                return e.getReplyId();
            }
            return null;
        }

        private static String blockIdOf(AgentEvent event) {
            if (event instanceof TextBlockStartEvent e) {
                return e.getBlockId();
            }
            if (event instanceof TextBlockDeltaEvent e) {
                return e.getBlockId();
            }
            if (event instanceof TextBlockEndEvent e) {
                return e.getBlockId();
            }
            if (event instanceof ThinkingBlockStartEvent e) {
                return e.getBlockId();
            }
            if (event instanceof ThinkingBlockDeltaEvent e) {
                return e.getBlockId();
            }
            if (event instanceof ThinkingBlockEndEvent e) {
                return e.getBlockId();
            }
            if (event instanceof DataBlockStartEvent e) {
                return e.getBlockId();
            }
            if (event instanceof DataBlockDeltaEvent e) {
                return e.getBlockId();
            }
            if (event instanceof DataBlockEndEvent e) {
                return e.getBlockId();
            }
            if (event instanceof HintBlockEvent e) {
                return e.getBlockId();
            }
            return null;
        }

        private static String toolCallIdOf(AgentEvent event) {
            if (event instanceof ToolCallStartEvent e) {
                return e.getToolCallId();
            }
            if (event instanceof ToolCallDeltaEvent e) {
                return e.getToolCallId();
            }
            if (event instanceof ToolCallEndEvent e) {
                return e.getToolCallId();
            }
            if (event instanceof ToolResultStartEvent e) {
                return e.getToolCallId();
            }
            if (event instanceof ToolResultTextDeltaEvent e) {
                return e.getToolCallId();
            }
            if (event instanceof ToolResultDataDeltaEvent e) {
                return e.getToolCallId();
            }
            if (event instanceof ToolResultEndEvent e) {
                return e.getToolCallId();
            }
            return null;
        }

        private static String deltaOf(AgentEvent event) {
            if (event instanceof TextBlockDeltaEvent e) {
                return e.getDelta();
            }
            if (event instanceof ThinkingBlockDeltaEvent e) {
                return e.getDelta();
            }
            if (event instanceof DataBlockDeltaEvent e) {
                return e.getDelta();
            }
            if (event instanceof ToolCallDeltaEvent e) {
                return e.getDelta();
            }
            if (event instanceof ToolResultTextDeltaEvent e) {
                return e.getDelta();
            }
            if (event instanceof ToolResultDataDeltaEvent e) {
                return String.valueOf(e.getData());
            }
            if (event instanceof ToolResultStartEvent e) {
                return "";
            }
            return null;
        }

        /**
         * 关闭时的终态取值。
         */
        private static io.agentscope.core.message.ToolResultState statusOf(
                AgentEvent event, SpanKind kind) {
            if (event instanceof ToolResultEndEvent e && e.getState() != null) {
                return e.getState();
            }
            if (event instanceof AllToolsDeniedEvent) {
                return io.agentscope.core.message.ToolResultState.DENIED;
            }
            if (event instanceof AgentEndEvent) {
                return io.agentscope.core.message.ToolResultState.SUCCESS;
            }
            return io.agentscope.core.message.ToolResultState.SUCCESS;
        }

        /**
         * 快照携带的负载（开启与关闭共用）。
         *
         * <p>为何开启快照也要带负载：消费者（协议投影、前端）构造「工具调用开始」消息时就需要
         * 工具标识与名称，不能等到关闭时才有。若只有关闭快照带负载，投影要么回查历史、
         * 要么自己解析稳定键格式 —— 两条路都把内部表示泄漏给了消费方。</p>
         */
        private Map<String, Object> payloadOf(AgentEvent event) {
            Map<String, Object> payload = new LinkedHashMap<>();
            if (event instanceof ModelCallEndEvent e && e.getUsage() != null) {
                payload.put("usage", e.getUsage());
            }
            // 工具调用标识：虽已编码进 stableKey，但消费者（协议投影、前端）需要的是可直接使用的
            // 字段值，不应要求它们去解析内部键格式。开快照与关快照都带上，使投影无需回查。
            String toolCallId = toolCallIdOf(event);
            if (toolCallId != null) {
                payload.put("toolCallId", toolCallId);
            }
            if (event instanceof ToolCallEndEvent e && e.getToolCallName() != null) {
                payload.put("toolName", e.getToolCallName());
            }
            if (event instanceof ToolResultEndEvent e && e.getToolCallName() != null) {
                payload.put("toolName", e.getToolCallName());
            }
            // 工具结果流的阶段标记。
            //
            // 归集把「调用」与「结果」并入同一个 TOOL_CALL 节点（它们共享 toolCallId，
            // 拆成两棵树反而割裂了因果），代价是节点内的快照失去了阶段区分 ——
            // 消费方拿到一串增量快照，无从判断哪条是结果的开头、哪条是结束。
            // 协议投影需要这个区分（前端要先收 tool_result_start 打开区域，再收 delta 追加），
            // 故在负载里记下产生该快照的事件类型，让阶段可恢复而不必回查事件流。
            if (event != null && event.getType() != null) {
                switch (event.getType()) {
                    case TOOL_RESULT_START -> payload.put("resultPhase", "start");
                    case TOOL_RESULT_TEXT_DELTA, TOOL_RESULT_DATA_DELTA ->
                            payload.put("resultPhase", "delta");
                    case TOOL_RESULT_END -> payload.put("resultPhase", "end");
                    default -> {
                        // 非工具结果事件不标注
                    }
                }
            }
            if (event instanceof RequireUserConfirmEvent e) {
                payload.put("toolCalls", e.getToolCalls());
            }
            if (event instanceof RequireExternalExecutionEvent e) {
                payload.put("toolCalls", e.getToolCalls());
            }
            if (event instanceof UserConfirmResultEvent e) {
                payload.put("confirmResults", e.getConfirmResults());
            }
            if (event instanceof ExternalExecutionResultEvent e) {
                payload.put("toolResults", e.getToolResults());
            }
            if (event instanceof ExceedMaxItersEvent e) {
                payload.put("maxIters", e.getMaxIters());
                payload.put("currentIter", e.getCurrentIter());
            }
            if (event instanceof AllToolsDeniedEvent e) {
                payload.put("deniedToolCalls", e.getDeniedToolCalls());
            }
            if (event instanceof RequestStopEvent e) {
                payload.put("reason", e.getReason());
                payload.put("generateReason", e.getGenerateReason());
            }
            if (event instanceof AgentResultEvent e) {
                payload.put("result", e.getResult());
            }
            if (event instanceof SubagentExposedEvent e) {
                payload.put("subagentId", e.getSubagentId());
                payload.put("agentId", e.getAgentId());
                payload.put("sessionId", e.getSessionId());
                payload.put("label", e.getLabel());
            }
            if (event instanceof HintBlockEvent e) {
                payload.put("hintSource", e.getHintSource());
                payload.put("hint", e.getHint());
            }
            if (event instanceof CustomEvent e) {
                payload.put("name", e.getName());
                payload.put("value", e.getValue());
            }
            if (event instanceof AgentStartEvent e) {
                payload.put("sessionId", e.getSessionId());
                payload.put("name", e.getName());
                payload.put("role", e.getRole());
            }
            return payload.isEmpty() ? null : payload;
        }
    }
}
