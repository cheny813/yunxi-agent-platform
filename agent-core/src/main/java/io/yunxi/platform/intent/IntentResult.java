package io.yunxi.platform.intent;

import java.util.List;

/**
 * 意图引擎统一结果。
 *
 * <p>{@link #sceneName()} 恒等于三级检测链输出（自定义场景→概念域→内置关键词→GENERAL），
 * 与意图命中与否无关——它服务于 MemoryScene 记忆保留策略，禁止用意图 label 覆盖。</p>
 *
 * @param originalQuery  原始 query
 * @param rewrittenQuery 改写后 query（改写关闭时 == originalQuery）
 * @param entities       NER 实体（可为空列表，永不为 null）
 * @param intent         意图（未命中为 Intent.unknown()）
 * @param routeHint      路由建议（可为 RouteHint.empty()）
 * @param sceneName      三级链场景名（兼容 MemoryScene 体系）
 * @param domain         解析出的领域名（sceneOnly 工厂为 null）
 * @param timings        阶段耗时
 * @param degraded       任一阶段降级为 true
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public record IntentResult(
        String originalQuery,
        String rewrittenQuery,
        List<Entity> entities,
        Intent intent,
        RouteHint routeHint,
        String sceneName,
        String domain,
        StageTimings timings,
        boolean degraded) {

    /** 开关关闭/全降级时的"仅场景"结果（K2） */
    public static IntentResult sceneOnly(String query, String sceneName) {
        return new IntentResult(query, query, List.of(), Intent.unknown(),
                RouteHint.empty(), sceneName, null, new StageTimings(0, 0, 0, 0, 0), true);
    }
}
