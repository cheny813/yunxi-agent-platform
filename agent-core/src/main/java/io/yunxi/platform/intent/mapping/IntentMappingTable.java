package io.yunxi.platform.intent.mapping;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.yunxi.platform.intent.RouteHint;
import io.yunxi.platform.intent.domain.DomainRuntime;
import io.yunxi.platform.intent.snapshot.MappingSnapshot;
import io.yunxi.platform.intent.util.IntentYaml;

/**
 * 意图映射表解析器宿主。
 *
 * <p>由 {@link #parse(Resource)} 从 YAML 解析映射（支持 {@code classpath:} /
 * {@code file:} 前缀，业务方可外部化到部署目录）。解析异常降级为空表（K9）。
 * {@link #routeFor(String, DomainRuntime)} 读合并后快照返回路由建议；
 * Mapping 支持可选 {@code priority}（缺省 0，冲突时高者优先）。
 * score 字段为路由建议评分。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Component
public class IntentMappingTable {

    private static final Logger log = LoggerFactory.getLogger(IntentMappingTable.class);

    /**
     * 解析映射表资源为不可变快照（K9 降级为空表，不抛出）。
     *
     * @param resource YAML 资源
     * @return 映射表快照（永不 null）
     */
    public MappingSnapshot parse(Resource resource) {
        if (resource == null || !resource.exists()) {
            log.warn("[INTENT] 意图映射表资源不存在，使用空表: {}（可按需配置 yunxi.intent.mapping-table 指向外部文件）", resource);
            return MappingSnapshot.empty();
        }
        try (InputStream in = resource.getInputStream()) {
            MappingDoc doc = IntentYaml.mapper().readValue(in, MappingDoc.class);
            List<Mapping> list = doc == null || doc.mappings == null ? List.of() : List.copyOf(doc.mappings);
            Map<String, Mapping> byIntent = new HashMap<>();
            for (Mapping m : list) {
                if (m.intent != null) {
                    byIntent.put(m.intent, m);
                }
            }
            log.info("[INTENT] 意图映射表解析成功: {} 条", list.size());
            return new MappingSnapshot(Map.copyOf(byIntent), list);
        } catch (Exception e) {
            log.error("[INTENT] 意图映射表解析失败，降级为空表: {}", e.getMessage());
            return MappingSnapshot.empty();
        }
    }

    /**
     * 意图 ID → RouteHint（读运行时映射快照，按 priority 裁决）。
     *
     * <p>score 透传意图置信——mode=rule 下规则命中恒 ≥0.9 > 默认门槛 0.5，行为与
     * 既有版本一致；mode=hybrid 下 LLM/低置信结果经 {@code min-route-score} 门槛过滤，
     * 误判改道风险由此受控。</p>
     *
     * @param intentId   意图 ID
     * @param confidence 意图置信度（透传为 RouteHint.score）
     * @param runtime    域运行时（null 时返回空提示）
     * @return 路由建议；未配置返回 RouteHint.empty()
     */
    public RouteHint routeFor(String intentId, double confidence, DomainRuntime runtime) {
        if (intentId == null || runtime == null) {
            return RouteHint.empty();
        }
        Mapping m = runtime.mapping().byIntent().get(intentId);
        if (m == null) {
            return RouteHint.empty();
        }
        return new RouteHint(m.agent, safe(m.experts), safe(m.toolGroups), safe(m.skills), confidence);
    }

    private List<String> safe(List<String> list) {
        return list == null ? List.of() : List.copyOf(list);
    }

    /** 映射文档（YAML 绑定 DTO） */
    public static class MappingDoc {
        @JsonProperty("intent-mappings")
        public List<Mapping> mappings;
    }

    /** 单条映射（支持可选 priority，缺省 0） */
    public static class Mapping {
        public String intent;
        public String agent;
        public List<String> experts;     // 可 null
        @JsonProperty("tool-groups")
        public List<String> toolGroups;  // 可 null
        public List<String> skills;      // 可 null

        /** 路由冲突裁决优先级（缺省 0，高者优先） */
        public Integer priority = 0;
    }
}
