package io.yunxi.platform.intent.ner;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.yunxi.platform.intent.config.IntentProperties;

/**
 * NER 实体词典加载器（M1 规则通道）。
 *
 * <p>通过 {@link ResourceLoader} 加载 YAML（默认 {@code intent/ner-dictionaries.yml}），
 * 支持 {@code classpath:} / {@code file:} / {@code url:} 前缀，业务方可将词典
 * 外部化到部署目录或配置中心。产出不可变词条列表（按 value 长度降序预排序）
 * 与编译后正则。任何解析异常均降级为空结构，不向外抛出（K9）。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Component
public class EntityDictionaryLoader {

    private static final Logger log = LoggerFactory.getLogger(EntityDictionaryLoader.class);

    private final IntentProperties properties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    private List<DictEntry> dictEntries = List.of();
    private List<CompiledRegex> regexes = List.of();
    private Map<String, Integer> typePriorities = Map.of();

    public EntityDictionaryLoader(IntentProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    public void init() {
        load();
    }

    /**
     * 加载词典（K9 降级：失败 → 空结构，不抛出）。
     */
    void load() {
        try {
            Resource resource = resourceLoader.getResource(properties.getNerDictionary());
            if (!resource.exists()) {
                log.warn("[INTENT] NER 词典资源不存在，使用空词典: {}（可按需配置 yunxi.intent.ner-dictionary 指向外部文件）", properties.getNerDictionary());
                return;
            }
            try (InputStream in = resource.getInputStream()) {
                DictionaryDoc doc = yamlMapper.readValue(in, DictionaryDoc.class);
                if (doc == null) {
                    log.error("[INTENT] NER 词典加载失败: 文档为空");
                    return;
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

                this.dictEntries = List.copyOf(entries);
                this.regexes = List.copyOf(regs);
                this.typePriorities = Map.copyOf(prios);
                log.info("[INTENT] NER 词典加载成功: {} 词条, {} 正则", entries.size(), regs.size());
            }
        } catch (Exception e) {
            log.error("[INTENT] NER 词典加载失败，NER 阶段降级为空: {}", e.getMessage());
            this.dictEntries = List.of();
            this.regexes = List.of();
            this.typePriorities = Map.of();
        }
    }

    public List<DictEntry> getDictEntries() {
        return dictEntries;
    }

    public List<CompiledRegex> getRegexes() {
        return regexes;
    }

    /** 实体类型优先级（未注册返回 0） */
    public int getPriority(String type) {
        return typePriorities.getOrDefault(type, 0);
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
