package io.yunxi.platform.intent.mapping;

import java.io.InputStream;
import java.util.List;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.yunxi.platform.intent.RouteHint;
import io.yunxi.platform.intent.config.IntentProperties;

/**
 * 意图映射表（意图 ID → 路由建议）。
 *
 * <p>通过 {@link ResourceLoader} 从 YAML 加载（支持 {@code classpath:} / {@code file:}
 * 前缀，业务方可将映射表外部化到部署目录）。任何解析异常降级为空表（K9）。
 * score 字段 M1 恒填 1.0。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Component
public class IntentMappingTable {

    private static final Logger log = LoggerFactory.getLogger(IntentMappingTable.class);

    private final IntentProperties properties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    private List<Mapping> mappings = List.of();

    public IntentMappingTable(IntentProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    void init() {
        load();
    }

    /**
     * 加载映射表（K9 降级为空表，不抛出）。
     */
    void load() {
        try {
            Resource resource = resourceLoader.getResource(properties.getMappingTable());
            if (!resource.exists()) {
                log.warn("[INTENT] 意图映射表资源不存在，使用空表: {}（可按需配置 yunxi.intent.mapping-table 指向外部文件）", properties.getMappingTable());
                return;
            }
            try (InputStream in = resource.getInputStream()) {
                MappingDoc doc = yamlMapper.readValue(in, MappingDoc.class);
                this.mappings = doc == null || doc.mappings == null ? List.of() : List.copyOf(doc.mappings);
                log.info("[INTENT] 意图映射表加载成功: {} 条", mappings.size());
            }
        } catch (Exception e) {
            log.error("[INTENT] 意图映射表加载失败，降级为空表: {}", e.getMessage());
            this.mappings = List.of();
        }
    }

    /**
     * 意图 ID → RouteHint；未配置返回 RouteHint.empty()。
     */
    public RouteHint routeFor(String intentId) {
        if (intentId == null) {
            return RouteHint.empty();
        }
        for (Mapping m : mappings) {
            if (intentId.equals(m.intent)) {
                return new RouteHint(m.agent, safe(m.experts), safe(m.toolGroups), safe(m.skills), 1.0);
            }
        }
        return RouteHint.empty();
    }

    private List<String> safe(List<String> list) {
        return list == null ? List.of() : List.copyOf(list);
    }

    /** 映射文档（YAML 绑定 DTO） */
    public static class MappingDoc {
        @JsonProperty("intent-mappings")
        public List<Mapping> mappings;
    }

    /** 单条映射 */
    public static class Mapping {
        public String intent;
        public String agent;
        public List<String> experts;     // 可 null
        @JsonProperty("tool-groups")
        public List<String> toolGroups;  // 可 null
        public List<String> skills;      // 可 null
    }
}
