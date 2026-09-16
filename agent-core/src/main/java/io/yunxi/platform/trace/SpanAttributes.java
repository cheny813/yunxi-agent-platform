package io.yunxi.platform.trace;

/**
 * 轨迹属性键。
 *
 * <p>用于承载「事件流里没有、只存在于中间件入参」的补充信息。这类信息无法由事件本身推导，
 * 只能由中间件在钩子内读取入参后写入，再由归集层合并进轨迹节点负载。</p>
 *
 * <p>属性写入按调用维度进行，因此以当前调用的会话槽位为作用域；同一会话的并发调用互不干扰。</p>
 *
 * @author yunxi-agent-platform
 */
public final class SpanAttributes {

    /**
     * 本次模型调用使用的模型名。
     *
     * <p>模型标识不在模型调用事件中，仅在中间件的调用入参里，故由此通道补充。</p>
     */
    public static final String MODEL = "model";

    /**
     * 本次模型调用使用的提供方。
     *
     * <p>与模型名同源，均取自中间件入参。</p>
     */
    public static final String PROVIDER = "provider";

    /**
     * 本次调用命中的技能名。
     *
     * <p>技能调用是「模型决定调用」这一意向，框架的技能使用记录也在调用返回前即产生，
     * 因此其归属信息需由中间件在动作阶段补充。</p>
     */
    public static final String SKILL = "skill";

    /**
     * 本次调用命中技能的归属来源，取值如工作区技能、内置技能等。
     */
    public static final String SKILL_SOURCE = "skillSource";

    private SpanAttributes() {
    }
}
