package io.yunxi.platform.intent.rewrite;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.yunxi.platform.intent.config.IntentProperties;

/**
 * 术语归一化改写处理器（M1）。
 *
 * <p>把 query 中出现的术语按术语表替换为统一说法
 * （如 "一周"→"一个星期"、"荤菜"→"畜禽肉类"）。按 key 长度降序处理，长词优先。
 * 通过 {@link ResourceLoader} 加载 YAML，支持 {@code classpath:} / {@code file:} 前缀，
 * 业务方可将术语表外部化到部署目录。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Component
public class TerminologyProcessor implements RewriteProcessor {

    private static final Logger log = LoggerFactory.getLogger(TerminologyProcessor.class);

    private final IntentProperties properties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    private Map<String, String> terms = Map.of();
    private List<String> sortedKeys = List.of();

    public TerminologyProcessor(IntentProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    void init() {
        try {
            Resource resource = resourceLoader.getResource(properties.getTerminologyTable());
            if (!resource.exists()) {
                log.warn("[INTENT] 术语表资源不存在，使用空术语表: {}（可按需配置 yunxi.intent.terminology-table 指向外部文件）", properties.getTerminologyTable());
                return;
            }
            try (InputStream in = resource.getInputStream()) {
                TermDoc doc = yamlMapper.readValue(in, TermDoc.class);
                if (doc == null || doc.terms == null) {
                    log.error("[INTENT] 术语表加载失败: 文档为空");
                    return;
                }
                Map<String, String> loaded = new HashMap<>(doc.terms);
                List<String> keys = new ArrayList<>(loaded.keySet());
                // 按 key 长度降序（长词优先防子串误替换）
                keys.sort((a, b) -> Integer.compare(b.length(), a.length()));
                this.terms = Map.copyOf(loaded);
                this.sortedKeys = List.copyOf(keys);
                log.info("[INTENT] 术语表加载成功: {} 条", loaded.size());
            }
        } catch (Exception e) {
            log.error("[INTENT] 术语表加载失败，改写降级为空: {}", e.getMessage());
            this.terms = Map.of();
            this.sortedKeys = List.of();
        }
    }

    @Override
    public String name() {
        return "terminology";
    }

    @Override
    public String process(String query, RewriteContext ctx) {
        if (query == null || sortedKeys.isEmpty()) {
            return null;
        }
        String result = query;
        boolean changed = false;
        for (String key : sortedKeys) {
            String value = terms.get(key);
            if (value != null && result.contains(key)) {
                result = result.replace(key, value);
                changed = true;
            }
        }
        return changed ? result : null;
    }

    /** 术语表文档（YAML 绑定 DTO） */
    public static class TermDoc {
        public Map<String, String> terms;
    }
}
