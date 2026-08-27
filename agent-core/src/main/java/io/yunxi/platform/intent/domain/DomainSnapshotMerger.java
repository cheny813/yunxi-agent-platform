package io.yunxi.platform.intent.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.yunxi.platform.intent.classify.IntentTree;
import io.yunxi.platform.intent.mapping.IntentMappingTable;
import io.yunxi.platform.intent.ner.EntityDictionaryLoader;
import io.yunxi.platform.intent.snapshot.EntityDictSnapshot;
import io.yunxi.platform.intent.snapshot.MappingSnapshot;
import io.yunxi.platform.intent.snapshot.TermSnapshot;
import io.yunxi.platform.intent.snapshot.TreeSnapshot;

/**
 * 快照合并器。
 *
 * <p>base + 业务域四份快照独立合并，业务域优先 + 冲突 warn。
 * 合并结果不可变，消费方零合并逻辑。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Component
public class DomainSnapshotMerger {

    private static final Logger log = LoggerFactory.getLogger(DomainSnapshotMerger.class);

    /**
     * 合并 base 与业务域运行时。
     *
     * @param base    基础域（必非 null）
     * @param overlay 业务域（必非 null；name 用于结果命名与日志）
     * @return 合并后运行时（name=overlay.name，version=overlay.version，loadedAt=overlay.loadedAt）
     */
    public DomainRuntime merge(DomainRuntime base, DomainRuntime overlay) {
        EntityDictSnapshot ner = mergeNer(base.ner(), overlay.ner());
        TermSnapshot terms = mergeTerms(base.terms(), overlay.terms());
        TreeSnapshot tree = mergeTree(base.tree(), overlay.tree());
        MappingSnapshot mapping = mergeMapping(base.mapping(), overlay.mapping());
        return new DomainRuntime(overlay.name(), overlay.version(), overlay.loadedAt(),
                ner, terms, tree, mapping);
    }

    /** NER 词典：按 type 合并词条（业务域同 type 词条优先）；typePriorities 业务域优先；regexes 并集（业务域优先） */
    EntityDictSnapshot mergeNer(EntityDictSnapshot base, EntityDictSnapshot overlay) {
        // 词条：同 (type,value) 业务域覆盖 base；保持 value 长度降序
        Map<String, EntityDictionaryLoader.DictEntry> byKey = new LinkedHashMap<>();
        for (EntityDictionaryLoader.DictEntry e : base.entries()) {
            byKey.putIfAbsent(key(e.type(), e.value()), e);
        }
        for (EntityDictionaryLoader.DictEntry e : overlay.entries()) {
            byKey.put(key(e.type(), e.value()), e);   // 业务域覆盖
        }
        List<EntityDictionaryLoader.DictEntry> entries = new ArrayList<>(byKey.values());
        entries.sort((a, b) -> Integer.compare(b.value().length(), a.value().length()));

        // 优先级：业务域优先
        Map<String, Integer> prios = new HashMap<>(base.typePriorities());
        prios.putAll(overlay.typePriorities());

        // 正则：并集（业务域按 type 覆盖）
        Map<String, EntityDictionaryLoader.CompiledRegex> regs = new LinkedHashMap<>();
        for (EntityDictionaryLoader.CompiledRegex r : base.regexes()) {
            regs.putIfAbsent(r.type(), r);
        }
        for (EntityDictionaryLoader.CompiledRegex r : overlay.regexes()) {
            regs.put(r.type(), r);
        }
        return new EntityDictSnapshot(List.copyOf(entries),
                List.copyOf(regs.values()), Map.copyOf(prios));
    }

    /** 术语表：按 term key 合并，同 key 业务域优先；sortedKeys 按长度降序重建 */
    TermSnapshot mergeTerms(TermSnapshot base, TermSnapshot overlay) {
        Map<String, String> terms = new HashMap<>(base.terms());
        terms.putAll(overlay.terms());
        List<String> keys = new ArrayList<>(terms.keySet());
        keys.sort((a, b) -> Integer.compare(b.length(), a.length()));
        return new TermSnapshot(Map.copyOf(terms), List.copyOf(keys));
    }

    /** 意图树：id 全局唯一，冲突业务域覆盖 base 并 warn；索引在合并后全量节点上重建 */
    TreeSnapshot mergeTree(TreeSnapshot base, TreeSnapshot overlay) {
        Map<String, IntentTree.Node> byId = new LinkedHashMap<>();
        for (IntentTree.Node n : base.allNodes()) {
            if (n.id != null) {
                byId.putIfAbsent(n.id, n);
            }
        }
        for (IntentTree.Node n : overlay.allNodes()) {
            if (n.id == null) {
                continue;
            }
            if (byId.containsKey(n.id)) {
                log.warn("[INTENT] 意图树节点 id 冲突，业务域覆盖 base: id={}", n.id);
            }
            byId.put(n.id, n);
        }
        List<IntentTree.Node> all = new ArrayList<>(byId.values());
        return TreeSnapshot.build(all);
    }

    /** 映射表：按 intent 合并，业务域优先 */
    MappingSnapshot mergeMapping(MappingSnapshot base, MappingSnapshot overlay) {
        Map<String, IntentMappingTable.Mapping> byIntent = new LinkedHashMap<>();
        for (IntentMappingTable.Mapping m : base.mappings()) {
            if (m.intent != null) {
                byIntent.putIfAbsent(m.intent, m);
            }
        }
        for (IntentMappingTable.Mapping m : overlay.mappings()) {
            if (m.intent != null) {
                byIntent.put(m.intent, m);
            }
        }
        return new MappingSnapshot(Map.copyOf(byIntent), List.copyOf(byIntent.values()));
    }

    private String key(String type, String value) {
        return type + "\u0000" + value;
    }
}
