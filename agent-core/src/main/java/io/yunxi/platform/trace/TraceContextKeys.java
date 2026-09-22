package io.yunxi.platform.trace;

import java.util.Map;

/**
 * 轨迹相关的运行时上下文键与载荷载体。
 *
 * <p>运行时上下文是「解析阶段」与「执行阶段」之间的唯一通道 —— 意图分析发生在调用之前，
 * 归集发生在调用期间，两者时序不重叠，只能经由上下文传递。</p>
 *
 * @author yunxi-agent-platform
 */
public final class TraceContextKeys {

    private TraceContextKeys() {
    }

    /** 轨迹标识在运行时上下文中的键，供编排层在发起调用前写入以覆盖默认取值 */
    public static final String TRACE_ID_KEY = "yunxi.trace.traceId";

    /** 待注入的意图节点数据在运行时上下文中的键（由编排层写入） */
    public static final String INTENT_SPAN_KEY = "yunxi.trace.intentSpan";

    /**
     * 编排层意图节点的载荷载体。
     *
     * <p>刻意做成极简的数据袋而非直接传意图引擎的类型：轨迹归集属于产品平面的观测件，
     * 不应依赖意图引擎的类型。载荷由编排层自行决定装什么，归集侧只负责把它挂到树上。</p>
     *
     * @param payload    节点负载
     * @param durationMs 节点耗时（毫秒）
     */
    public record IntentSpanData(Map<String, Object> payload, long durationMs) {
    }

    /**
     * 轨迹标识的注入键。
     *
     * @return 运行时上下文中轨迹标识的属性键
     */
    public static String traceIdKey() {
        return TRACE_ID_KEY;
    }
}
