package io.yunxi.platform.ingress.trace;

import io.yunxi.platform.trace.TraceCollector;
import io.yunxi.platform.trace.TraceStore;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 轨迹链路诊断端点。
 *
 * <p>归集链路的故障有三种形态：会话未开启（标识没解析出来）、归集器未被调用（通道没接线）、
 * 落库失败（存储侧问题）。三者在日志上都只表现为「查不到轨迹」，无法区分。本端点把这三层
 * 的当前状态直接暴露出来，使判断不依赖日志推断。</p>
 *
 * <p>只读，不返回业务数据，仅返回计数器与存储类型。</p>
 */
@RestController
@RequestMapping("/api/traces/diagnostics")
public class TraceDiagnosticsController {

    private final TraceCollector traceCollector;
    private final TraceStore traceStore;

    public TraceDiagnosticsController(TraceCollector traceCollector, TraceStore traceStore) {
        this.traceCollector = traceCollector;
        this.traceStore = traceStore;
    }

    /**
     * 返回归集链路状态。
     *
     * <p>判读方式：{@code receivedEvents} 为 0 说明归集器从未被调用（通道未接线）；
     * 非 0 而 {@code persistedSpans} 为 0 说明归集产生了事件但没有快照可落库（归集逻辑问题）；
     * 两者都非 0 而 {@code recentTraceIds} 为空则说明落库失败（存储侧问题）。</p>
     *
     * @return 状态映射
     */
    @GetMapping
    public Map<String, Object> diagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("storeType", traceStore.getClass().getSimpleName());
        result.put("storeEndpoint", traceStore.storeEndpoint());
        result.put("receivedEvents", traceCollector.receivedEventCount());
        result.put("persistedSpans", traceCollector.persistedSpanCount());
        result.put("recentTraceIds", traceStore.recentTraceIds(10));
        result.put("hint",
                "storeEndpoint 表示轨迹实际落库的 Redis 实例（与装配日志一致）。"
                        + "若此处显示内存或与你预期的连接参数不符，说明应用连的 Redis 与预期实例不是同一个，"
                        + "查询不到轨迹属正常，请核对 spring.data.redis.host/port");
        return result;
    }
}
