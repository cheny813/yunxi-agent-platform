package io.yunxi.platform.intent.rewrite;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import io.yunxi.platform.intent.snapshot.TermSnapshot;
import io.yunxi.platform.intent.util.IntentYaml;

/**
 * 术语归一化改写处理器。
 *
 * <p>把 query 中出现的术语按术语表替换为统一说法
 * （如 "一周"→"一个星期"、"荤菜"→"畜禽肉类"）。按 key 长度降序处理，长词优先。
 * 数据自 {@link RewriteContext#runtime()} 的术语快照读取；快照由
 * {@link #parse(Resource)} 从 YAML 解析，支持 {@code classpath:} / {@code file:} 前缀。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Component
public class TerminologyProcessor implements RewriteProcessor {

    private static final Logger log = LoggerFactory.getLogger(TerminologyProcessor.class);

    /**
     * 解析术语表资源为不可变快照（失败降级为空快照）。
     *
     * @param resource YAML 资源
     * @return 术语表快照（永不 null）
     */
    public TermSnapshot parse(Resource resource) {
        if (resource == null || !resource.exists()) {
            log.warn("[INTENT] 术语表资源不存在，使用空术语表: {}", resource);
            return TermSnapshot.empty();
        }
        try (InputStream in = resource.getInputStream()) {
            TermDoc doc = IntentYaml.mapper().readValue(in, TermDoc.class);
            if (doc == null || doc.terms == null) {
                log.error("[INTENT] 术语表加载失败: 文档为空");
                return TermSnapshot.empty();
            }
            Map<String, String> loaded = Map.copyOf(doc.terms);
            List<String> keys = new ArrayList<>(loaded.keySet());
            // 按 key 长度降序（长词优先防子串误替换）
            keys.sort((a, b) -> Integer.compare(b.length(), a.length()));
            log.info("[INTENT] 术语表解析成功: {} 条", loaded.size());
            return new TermSnapshot(loaded, List.copyOf(keys));
        } catch (Exception e) {
            log.error("[INTENT] 术语表解析失败，改写降级为空: {}", e.getMessage());
            return TermSnapshot.empty();
        }
    }

    @Override
    public String name() {
        return "terminology";
    }

    @Override
    public String process(String query, RewriteContext ctx) {
        if (query == null || ctx.runtime() == null) {
            return null;
        }
        List<String> sortedKeys = ctx.runtime().terms().sortedKeys();
        if (sortedKeys.isEmpty()) {
            return null;
        }
        Map<String, String> terms = ctx.runtime().terms().terms();
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
