package io.yunxi.platform.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
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
import io.agentscope.core.message.ToolResultState;

/**
 * 归集测试用的事件流构造。
 *
 * <p>按真实事件排列构造事件流，用于离线回归验证配对与建树。代表性场景分为三类：
 * 完整回合（含文本、思考、工具与结果）、人机交互（确认与外部执行）、异常与边界
 * （超出迭代上限、全部工具被拒、请求停止）。</p>
 *
 * @author yunxi-agent-platform
 */
final class EventFixtures {

    private EventFixtures() {
    }

    /**
     * 触发全部 31 个事件类型各一次，用于映射覆盖度校验。
     *
     * @return 事件流
     */
    static List<AgentEvent> allEventTypes() {
        List<AgentEvent> events = new ArrayList<>();
        String reply = "r1";
        String block = "b1";
        String tool = "t1";

        events.add(new AgentStartEvent("s1", reply, "assistant"));
        events.add(new ModelCallStartEvent(reply));
        events.add(new ThinkingBlockStartEvent(reply, block));
        events.add(new ThinkingBlockDeltaEvent(reply, block, "思考中"));
        events.add(new ThinkingBlockEndEvent(reply, block));
        events.add(new ModelCallEndEvent(reply, null));
        events.add(new ToolCallStartEvent(reply, tool, "calculator"));
        events.add(new ToolCallDeltaEvent(reply, tool, "calculator", "{\"a\":"));
        events.add(new ToolCallEndEvent(reply, tool, "calculator"));
        events.add(new ToolResultStartEvent(reply, tool, "calculator"));
        events.add(new ToolResultTextDeltaEvent(reply, tool, "calculator", "结果"));
        events.add(new ToolResultDataDeltaEvent(reply, tool, "calculator",
                io.agentscope.core.message.TextBlock.builder().text("{\"v\":1}").build()));
        events.add(new ToolResultEndEvent(reply, tool, "calculator", ToolResultState.SUCCESS));
        events.add(new TextBlockStartEvent(reply, block));
        events.add(new TextBlockDeltaEvent(reply, block, "回答"));
        events.add(new TextBlockEndEvent(reply, block));
        events.add(new DataBlockStartEvent(reply, block));
        events.add(new DataBlockDeltaEvent(reply, block, "数据"));
        events.add(new DataBlockEndEvent(reply, block));
        events.add(new RequireUserConfirmEvent(reply, List.of()));
        events.add(new UserConfirmResultEvent(reply, List.of()));
        events.add(new RequireExternalExecutionEvent(reply, List.of()));
        events.add(new ExternalExecutionResultEvent(reply, List.of()));
        events.add(new SubagentExposedEvent("sa1", "a1", "s1", "研究"));
        events.add(new ExceedMaxItersEvent(reply, 10, 10));
        events.add(new AllToolsDeniedEvent(List.of()));
        events.add(new RequestStopEvent("用户取消", null));
        events.add(new HintBlockEvent(reply, block, "system", "提示"));
        events.add(new CustomEvent("agent_status", Map.of("phase", "ANSWER")));
        events.add(new AgentResultEvent(null));
        events.add(new AgentEndEvent(reply));
        return events;
    }

    /**
     * 完整回合：思考 → 工具调用（含结果）→ 文本回答。
     *
     * @return 事件流
     */
    static List<AgentEvent> normalTurn() {
        String reply = "r1";
        return List.of(
                new AgentStartEvent("s1", reply, "助手"),
                new ModelCallStartEvent(reply),
                new ThinkingBlockStartEvent(reply, "think-1"),
                new ThinkingBlockDeltaEvent(reply, "think-1", "先算一下"),
                new ThinkingBlockEndEvent(reply, "think-1"),
                new ModelCallEndEvent(reply, null),
                new ToolCallStartEvent(reply, "tool-1", "calculator"),
                new ToolCallEndEvent(reply, "tool-1", "calculator"),
                new ToolResultStartEvent(reply, "tool-1", "calculator"),
                new ToolResultTextDeltaEvent(reply, "tool-1", "calculator", "42"),
                new ToolResultEndEvent(reply, "tool-1", "calculator", ToolResultState.SUCCESS),
                new TextBlockStartEvent(reply, "text-1"),
                new TextBlockDeltaEvent(reply, "text-1", "答案是"),
                new TextBlockDeltaEvent(reply, "text-1", " 42"),
                new TextBlockEndEvent(reply, "text-1"),
                new AgentEndEvent(reply));
    }

    /**
     * 人机交互已闭环：确认请求与确认结果成对出现。
     *
     * @return 事件流
     */
    static List<AgentEvent> hitlResolved() {
        String reply = "r2";
        return List.of(
                new AgentStartEvent("s1", reply, "助手"),
                new RequireUserConfirmEvent(reply, List.of()),
                new UserConfirmResultEvent(reply, List.of()),
                new AgentEndEvent(reply));
    }

    /**
     * 人机交互未闭环：回合已结束但确认请求未获回应，应记为未决。
     *
     * @return 事件流
     */
    static List<AgentEvent> hitlPending() {
        String reply = "r3";
        return List.of(
                new AgentStartEvent("s1", reply, "助手"),
                new RequireUserConfirmEvent(reply, List.of()),
                new AgentEndEvent(reply));
    }

    /**
     * 异常与边界：超出迭代上限、全部工具被拒、请求停止。
     *
     * @return 事件流
     */
    static List<AgentEvent> boundarySignals() {
        String reply = "r4";
        return List.of(
                new AgentStartEvent("s1", reply, "助手"),
                new AllToolsDeniedEvent(List.of()),
                new ExceedMaxItersEvent(reply, 10, 10),
                new RequestStopEvent("达到上限", null),
                new AgentEndEvent(reply));
    }

    /**
     * 子代理事件：来源路径非空，用于验证层级推导。
     *
     * @return 事件流
     */
    static List<AgentEvent> subagentForwarded() {
        String reply = "r5";
        AgentStartEvent child = (AgentStartEvent) new AgentStartEvent("s2", reply, "子助手")
                .withSource("main/researcher");
        AgentEndEvent childEnd = (AgentEndEvent) new AgentEndEvent(reply)
                .withSource("main/researcher");
        return List.of(
                new AgentStartEvent("s1", reply, "助手"),
                child,
                childEnd,
                new AgentEndEvent(reply));
    }

    /**
     * 孤儿增量：没有任何开始事件的增量，应被丢弃而不猜测归属。
     *
     * @return 事件流
     */
    static List<AgentEvent> orphanDelta() {
        return List.of(
                new AgentStartEvent("s1", "r6", "助手"),
                new TextBlockDeltaEvent("r6", "missing-block", "孤立片段"),
                new AgentEndEvent("r6"));
    }

    /**
     * 不完整流：事件流中断，仍有节点未关闭。
     *
     * @return 事件流
     */
    static List<AgentEvent> truncatedStream() {
        String reply = "r7";
        return List.of(
                new AgentStartEvent("s1", reply, "助手"),
                new TextBlockStartEvent(reply, "text-x"));
    }
}
