package io.yunxi.platform.intent.ner;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.yunxi.platform.intent.snapshot.EntityDictSnapshot;
import io.yunxi.platform.intent.util.IntentYaml;

/**
 * NER 实体词典解析器宿主。
 *
 * <p>由 {@link EntityDictionaryLoader#parse(Resource)} 将词典 YAML 解析为不可变
 * {@link EntityDictSnapshot}：词条按 value 长度降序（长词优先防子串误匹配）、
 * 正则编译、类型优先级提取。解析失败降级为空快照（K9），不向外抛出。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Component
public class EntityDictionaryLoader {

    private static final Logger log = LoggerFactory.getLogger(EntityDictionaryLoader.class);

    /**
     * 解析词典资源为不可变快照（K9 降级：失败 → 空快照）。
     *
     * @param resource YAML 资源（classpath:/file:/url:）
     * @return 实体词典快照（永不 null）
     */
    public EntityDictSnapshot parse(Resource resource) {
        if (resource == null || !resource.exists()) {
            log.warn("[INTENT] NER 词典资源不存在，使用空词典: {}", resource);
            return EntityDictSnapshot.empty();
        }
        try (InputStream in = resource.getInputStream()) {
            DictionaryDoc doc = IntentYaml.mapper().readValue(in, DictionaryDoc.class);
            if (doc == null) {
                log.error("[INTENT] NER 词典加载失败: 文档为空");
                return EntityDictSnapshot.empty();
            }

            Map<String, Integer> prios = new HashMap<>();
            if (doc.entityTypes != null) {
                for (Map.Entry<String, Map<String, Integer>> e : doc.entityTypes.entrySet()) {
                    Integer p = e.getValue() == null ? null : e.getValue().get("priority");
                    prios.put(e.getKey(), p == null ? 0 : p);
                }
            }

            List<DictEntry> entries = new ArrayList<>();
            if (doc.dictionaries != null) {
                for (Map.Entry<String, List<String>> e : doc.dictionaries.entrySet()) {
                    String type = e.getKey();
                    int prio = prios.getOrDefault(type, 0);
                    if (e.getValue() != null) {
                        for (String value : e.getValue()) {
                            entries.add(new DictEntry(type, value, prio));
                        }
                    }
                }
            }
            // 按 value 长度降序（长词优先，防子串误匹配）
            entries.sort((a, b) -> Integer.compare(b.value().length(), a.value().length()));

            List<CompiledRegex> regs = new ArrayList<>();
            if (doc.regexes != null) {
                for (RegexEntry r : doc.regexes) {
                    if (r.pattern == null) {
                        continue;
                    }
                    regs.add(new CompiledRegex(r.type, Pattern.compile(r.pattern)));
                }
            }

            log.info("[INTENT] NER 词典解析成功: {} 词条, {} 正则", entries.size(), regs.size());
            return new EntityDictSnapshot(List.copyOf(entries), List.copyOf(regs), Map.copyOf(prios));
        } catch (Exception e) {
            log.error("[INTENT] NER 词典解析失败，NER 阶段降级为空: {}", e.getMessage());
            return EntityDictSnapshot.empty();
        }
    }

    /** 词典词条（已按 value 长度降序） */
    public record DictEntry(String type, String value, int priority) {
    }

    /** 编译后正则 */
    public record CompiledRegex(String type, Pattern pattern) {
    }

    /** 词典文档（YAML 绑定 DTO） */
    public static class DictionaryDoc {
        @JsonProperty("entity-types")
        public Map<String, Map<String, Integer>> entityTypes;   // type → {priority}
        public Map<String, List<String>> dictionaries;          // type → 词条
        public List<RegexEntry> regexes;
    }

    /** 正则条目 */
    public static class RegexEntry {
        public String type;
        public String pattern;
    }
}
