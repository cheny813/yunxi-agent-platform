package io.yunxi.platform.aistio.orchestration;

import java.util.List;

/**
 * 触发能力自查结果（启动期无副作用探测结论）。
 *
 * @param available  入口是否可达且配置齐备
 * @param reason     结论说明（不可用时给出可执行的运维指引）
 * @param problems   逐个 definitionKey 的探测结论（无问题时为空）
 */
public record OrchestrationCapability(boolean available, String reason, List<String> problems) {

    public static OrchestrationCapability ok(String reason) {
        return new OrchestrationCapability(true, reason, List.of());
    }

    /** 可用，但保留逐项探测结论供启动日志/排障查看 */
    public static OrchestrationCapability ok(String reason, List<String> problems) {
        return new OrchestrationCapability(true, reason, problems);
    }

    public static OrchestrationCapability unavailable(String reason, List<String> problems) {
        return new OrchestrationCapability(false, reason, problems);
    }
}
