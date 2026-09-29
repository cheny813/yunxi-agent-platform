package io.yunxi.platform.trace.projection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * 轨迹投影注册表。
 *
 * <p>DI 收集全部 {@link TraceProjection} 实现，按 {@link TraceProjection#name()} 建立名称到实现的路由表。
 * 消费方（执行引擎、重放端点）只依赖本注册表，按协议名取投影，不再硬编码具体实现类——
 * 从而真正兑现「新增一个协议 = 新增一个 {@code TraceProjection} 实现类，内核零改动」的开放封闭原则。</p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class ProjectionRegistry {

    private final Map<String, TraceProjection<?>> byName = new LinkedHashMap<>();

    public ProjectionRegistry(List<TraceProjection<?>> projections) {
        if (projections != null) {
            for (TraceProjection<?> projection : projections) {
                byName.put(projection.name(), projection);
            }
        }
    }

    /** 按协议名取投影（泛型安全，调用点由赋值目标推断 {@code OUT}）。 */
    @SuppressWarnings("unchecked")
    public <T> TraceProjection<T> require(String name) {
        TraceProjection<?> projection = byName.get(name);
        if (projection == null) {
            throw new IllegalArgumentException(
                    "未注册的轨迹投影协议: " + name + "，已注册: " + byName.keySet());
        }
        return (TraceProjection<T>) projection;
    }

    public Optional<TraceProjection<?>> get(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public List<String> names() {
        return List.copyOf(byName.keySet());
    }
}
