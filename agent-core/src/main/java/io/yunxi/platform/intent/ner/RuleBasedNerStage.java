package io.yunxi.platform.intent.ner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import org.springframework.stereotype.Component;

import io.yunxi.platform.intent.Entity;
import io.yunxi.platform.intent.domain.DomainRuntime;
import io.yunxi.platform.intent.ner.EntityDictionaryLoader.CompiledRegex;
import io.yunxi.platform.intent.ner.EntityDictionaryLoader.DictEntry;
import io.yunxi.platform.intent.snapshot.EntityDictSnapshot;

/**
 * 规则版 NER 阶段。
 *
 * <p>算法：1) 词典最长匹配（词条按长度降序，大小写不敏感）；2) 正则匹配；
 * 3) 同 span 去重（保留优先级高者，词典条目 priority 来自 entity-types，正则视为 0）。
 * 数据自 {@link DomainRuntime#ner()} 快照读取（多域隔离）。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Component
public class RuleBasedNerStage implements NerStage {

    @Override
    public List<Entity> extract(String query, DomainRuntime runtime) {
        if (query == null || query.isBlank() || runtime == null) {
            return List.of();
        }
        EntityDictSnapshot snapshot = runtime.ner();
        if (snapshot == null) {
            return List.of();
        }
        String lower = query.toLowerCase();
        List<Entity> results = new ArrayList<>();

        // 1) 词典最长匹配（词条已按长度降序，长词优先）
        for (DictEntry entry : snapshot.entries()) {
            String lowerValue = entry.value().toLowerCase();
            int idx = lower.indexOf(lowerValue);
            if (idx >= 0) {
                results.add(new Entity(entry.type(),
                        query.substring(idx, idx + entry.value().length()),
                        normalize(entry.value()),
                        idx, idx + entry.value().length(), "DICT"));
            }
        }

        // 2) 正则匹配
        for (CompiledRegex cr : snapshot.regexes()) {
            Matcher m = cr.pattern().matcher(query);
            while (m.find()) {
                results.add(new Entity(cr.type(), m.group(), normalize(m.group()),
                        m.start(), m.end(), "REGEX"));
            }
        }

        // 3) 同 span 去重：保留 priority 高者
        Map<String, Entity> bySpan = new LinkedHashMap<>();
        for (Entity e : results) {
            String key = e.start() + ":" + e.end();
            Entity existing = bySpan.get(key);
            if (existing == null) {
                bySpan.put(key, e);
            } else {
                int newPrio = priorityOf(e, snapshot);
                int oldPrio = priorityOf(existing, snapshot);
                if (newPrio > oldPrio) {
                    bySpan.put(key, e);
                }
            }
        }
        return List.copyOf(bySpan.values());
    }

    private int priorityOf(Entity e, EntityDictSnapshot snapshot) {
        if (!"DICT".equals(e.source())) {
            return 0;
        }
        return snapshot.typePriorities().getOrDefault(e.type(), 0);
    }

    /** trim → 全角转半角 → toLowerCase */
    private String normalize(String s) {
        String t = s == null ? "" : s.trim();
        StringBuilder sb = new StringBuilder(t.length());
        for (char c : t.toCharArray()) {
            if (c == '\u3000') {
                sb.append(' ');
            } else if (c >= '\uFF01' && c <= '\uFF5E') {
                sb.append((char) (c - 0xFEE0));
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }
}
