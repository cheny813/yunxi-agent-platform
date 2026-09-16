package io.yunxi.platform.trace;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;

/**
 * 轨迹属性袋。
 *
 * <p>承载「事件流里没有、只存在于中间件入参」的补充信息，由中间件在钩子内写入，
 * 由归集层读取并合并进轨迹节点负载。</p>
 *
 * <p>按调用维度存放（键为会话槽位），因此同一会话的并发调用互不干扰，也无需清理。
 * 写入内容仅作观测用，不参与执行决策。</p>
 *
 * @author yunxi-agent-platform
 */
public final class SpanAttributeBag {

    /** 属性袋在运行时上下文中的键 */
    private static final String BAG_KEY = "yunxi.trace.spanAttributes";

    private final Map<String, Object> attributes = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, Map<String, Object>> skillByToolCall = Collections.synchronizedMap(new LinkedHashMap<>());

    private SpanAttributeBag() {
    }

    /**
     * 从运行时上下文取属性袋，不存在时创建并挂载。
     *
     * <p>运行时上下文的属性按调用维度隔离，因此同一会话的并发调用各自获得独立属性袋。</p>
     *
     * @param ctx 运行时上下文
     * @return 属性袋；上下文为空时返回一个不与上下文关联的实例，写入不生效但不影响调用方
     */
    @SuppressWarnings("unchecked")
    public static SpanAttributeBag of(RuntimeContext ctx) {
        if (ctx == null) {
            return new SpanAttributeBag();
        }
        Object existing = ctx.get(BAG_KEY);
        if (existing instanceof SpanAttributeBag bag) {
            return bag;
        }
        SpanAttributeBag created = new SpanAttributeBag();
        ctx.put(BAG_KEY, created);
        return created;
    }

    /**
     * 写入本次模型调用的模型与提供方。
     *
     * @param modelName 模型名
     * @param provider  提供方
     */
    public void putModel(String modelName, String provider) {
        if (modelName != null && !modelName.isBlank()) {
            attributes.put(SpanAttributes.MODEL, modelName);
        }
        if (provider != null && !provider.isBlank()) {
            attributes.put(SpanAttributes.PROVIDER, provider);
        }
    }

    /**
     * 记录一次技能命中。
     *
     * <p>技能调用的意向由框架的技能使用记录表达，在动作阶段产生；此处按工具调用标识记录，
     * 使归集层能把技能归属挂到对应的工具调用节点上。</p>
     *
     * @param toolUse   工具调用块
     * @param skillName 技能名
     * @param source    技能来源
     */
    public void putSkill(ToolUseBlock toolUse, String skillName, String source) {
        if (toolUse == null || skillName == null || skillName.isBlank()) {
            return;
        }
        Map<String, Object> skill = new LinkedHashMap<>();
        skill.put(SpanAttributes.SKILL, skillName);
        if (source != null && !source.isBlank()) {
            skill.put(SpanAttributes.SKILL_SOURCE, source);
        }
        skillByToolCall.put(toolUse.getId(), skill);
    }

    /**
     * 读取本次调用的补充属性快照。
     *
     * @return 属性快照；无内容时返回空 Map
     */
    public Map<String, Object> snapshot() {
        return new LinkedHashMap<>(attributes);
    }

    /**
     * 读取某个工具调用关联的技能归属。
     *
     * @param toolCallId 工具调用标识
     * @return 技能归属；无记录时返回 null
     */
    public Map<String, Object> skillOf(String toolCallId) {
        if (toolCallId == null) {
            return null;
        }
        Map<String, Object> skill = skillByToolCall.get(toolCallId);
        return skill == null ? null : new LinkedHashMap<>(skill);
    }

    /**
     * 是否存在补充属性。
     *
     * @return 存在返回 true
     */
    public boolean isEmpty() {
        return attributes.isEmpty();
    }
}
