package io.yunxi.platform.intent.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import io.yunxi.platform.intent.classify.IntentTree;
import io.yunxi.platform.intent.config.IntentProperties;
import io.yunxi.platform.intent.config.IntentProperties.DomainSource;
import io.yunxi.platform.intent.mapping.IntentMappingTable;
import io.yunxi.platform.intent.ner.EntityDictionaryLoader;
import io.yunxi.platform.intent.rewrite.TerminologyProcessor;

/**
 * 域运行时容器。
 *
 * <p>按 {@code yunxi.intent.domains.*} 加载全部域：base 必在（缺省由旧顶层字段构造，
 * 单域部署零迁移）；业务域只列差异文件、缺省项继承 base。业务域运行时为
 * base+自身合并后的最终视图（{@link DomainSnapshotMerger}），消费方零合并逻辑。
 * 未知域回落 base。热更新按域粒度 synchronized 串行，原子交换引用。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Component
public class DomainRegistry {

    private static final Logger log = LoggerFactory.getLogger(DomainRegistry.class);

    /** base 域运行时（合并后视图，business 域合并缓存基于它） */
    private volatile DomainRuntime base;

    /** 业务域合并后运行时（base+own 合并视图），value 为合并结果 */
    private final ConcurrentHashMap<String, DomainRuntime> merged = new ConcurrentHashMap<>();

    /** 业务域自身运行时（未合并，reload 用；name → own runtime） */
    private final ConcurrentHashMap<String, DomainRuntime> own = new ConcurrentHashMap<>();

    /** 域配置源（base 已折叠为四份有效资源路径） */
    private final Map<String, DomainSource> sources = new LinkedHashMap<>();

    /** 版本号发生器（每域一把，reload +1） */
    private final Map<String, AtomicLong> versions = new ConcurrentHashMap<>();

    /** 热更新锁：base 域一把，业务域各自一把 */
    private final Object baseLock = new Object();
    private final ConcurrentHashMap<String, Object> domainLocks = new ConcurrentHashMap<>();

    private final IntentProperties properties;
    private final DomainSnapshotMerger merger;
    private final EntityDictionaryLoader nerParser;
    private final TerminologyProcessor termParser;
    private final IntentTree treeParser;
    private final IntentMappingTable mappingParser;
    private final ResourceLoader resourceLoader;

    public DomainRegistry(IntentProperties properties,
            DomainSnapshotMerger merger,
            EntityDictionaryLoader nerParser,
            TerminologyProcessor termParser,
            IntentTree treeParser,
            IntentMappingTable mappingParser) {
        this.properties = properties;
        this.merger = merger;
        this.nerParser = nerParser;
        this.termParser = termParser;
        this.treeParser = treeParser;
        this.mappingParser = mappingParser;
        this.resourceLoader = new DefaultResourceLoader();
    }

    @PostConstruct
    public void init() {
        Map<String, DomainSource> configured = properties.getDomains();
        if (configured == null || configured.isEmpty()) {
            // 单域部署：旧顶层字段 → base 域快捷构造（零迁移）
            DomainSource baseSource = new DomainSource();
            baseSource.setNerDictionary(properties.getNerDictionary());
            baseSource.setTerminologyTable(properties.getTerminologyTable());
            baseSource.setIntentTree(properties.getIntentTree());
            baseSource.setMappingTable(properties.getMappingTable());
            configured = Map.of(DomainRuntime.BASE, baseSource);
        }
        // base 域必在；业务域缺省项继承 base
        DomainSource baseSource = configured.get(DomainRuntime.BASE);
        if (baseSource == null) {
            log.warn("[INTENT] domains 配置缺少 base 域，用旧顶层字段构造 base");
            baseSource = new DomainSource();
            baseSource.setNerDictionary(properties.getNerDictionary());
            baseSource.setTerminologyTable(properties.getTerminologyTable());
            baseSource.setIntentTree(properties.getIntentTree());
            baseSource.setMappingTable(properties.getMappingTable());
        }
        // 折叠：业务域 null 字段继承 base
        for (Map.Entry<String, DomainSource> e : configured.entrySet()) {
            DomainSource src = e.getValue();
            DomainSource resolved = new DomainSource();
            resolved.setNerDictionary(nonNull(src.getNerDictionary(), baseSource.getNerDictionary()));
            resolved.setTerminologyTable(nonNull(src.getTerminologyTable(), baseSource.getTerminologyTable()));
            resolved.setIntentTree(nonNull(src.getIntentTree(), baseSource.getIntentTree()));
            resolved.setMappingTable(nonNull(src.getMappingTable(), baseSource.getMappingTable()));
            sources.put(e.getKey(), resolved);
        }
        // 加载 base 域
        this.base = loadBase();
        log.info("[INTENT] DomainRegistry 初始化完成，域: {}", sources.keySet());
    }

    /** 取合并后运行时；未知域回落 base */
    public DomainRuntime runtime(String domain) {
        if (domain == null || domain.isBlank() || DomainRuntime.BASE.equals(domain)) {
            return base;
        }
        DomainRuntime mergedView = merged.get(domain);
        if (mergedView != null) {
            return mergedView;
        }
        // 按需重合并（该域已加载但合并缓存被 base reload 失效）
        synchronized (domainLock(domain)) {
            mergedView = merged.get(domain);
            if (mergedView == null) {
                DomainRuntime ownView = own.get(domain);
                if (ownView == null) {
                    return base;
                }
                mergedView = merger.merge(base, ownView);
                merged.put(domain, mergedView);
            }
            return mergedView;
        }
    }

    /** 全部域名（含 base） */
    public Set<String> domains() {
        return sources.keySet();
    }

    /**
     * 各域源文件位置（已折叠继承：业务域四字段均已解析到 base 或自身）。
     * 供 {@code IntentFilePoller} 按 {@code file:} 前缀轮询 mtime。
     */
    public Map<String, List<String>> sourceLocations() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        sources.forEach((domain, src) -> out.put(domain, List.of(
                src.getNerDictionary(), src.getTerminologyTable(),
                src.getIntentTree(), src.getMappingTable())));
        return out;
    }

    /** 热更新：按域重新解析四份 YAML → 自洽校验 → 原子交换 */
    public ReloadResult reload(String domain, boolean force) {
        if (!properties.getReload().isEnabled()) {
            return ReloadResult.fail(domain, versionOf(domain), "reload disabled by yunxi.intent.reload.enabled=false");
        }
        if (domain == null || domain.isBlank()) {
            List<ReloadResult> results = reloadAll(force);
            return results.isEmpty()
                    ? ReloadResult.fail("base", versionOf("base"), "no domains to reload")
                    : results.get(results.size() - 1);
        }
        if (!sources.containsKey(domain)) {
            return ReloadResult.fail(domain, versionOf(domain), "unknown domain: " + domain);
        }
        Object lock = DomainRuntime.BASE.equals(domain) ? baseLock : domainLock(domain);
        synchronized (lock) {
            try {
                DomainRuntime fresh = parseDomain(domain);
                if (fresh == null) {
                    return ReloadResult.fail(domain, versionOf(domain), "parse failed, keep old snapshot");
                }
                if (DomainRuntime.BASE.equals(domain)) {
                    this.base = fresh;
                    // base 变化 → 失效所有业务域合并缓存，触发按需重合并
                    merged.clear();
                } else {
                    own.put(domain, fresh);
                    merged.put(domain, merger.merge(base, fresh));
                }
                long v = versions.computeIfAbsent(domain, k -> new AtomicLong(0)).incrementAndGet();
                log.info("[INTENT] reload 完成: domain={}, newVersion={}", domain, v);
                return ReloadResult.ok(domain, v);
            } catch (Exception e) {
                log.error("[INTENT] reload 失败: domain={}, {}", domain, e.getMessage(), e);
                return ReloadResult.fail(domain, versionOf(domain), e.getMessage());
            }
        }
    }

    /**
     * 全量 reload（每域结果逐条返回，供审计 / 监听器逐域消费；任一域失败不阻断后续域）。
     */
    public List<ReloadResult> reloadAll(boolean force) {
        List<ReloadResult> results = new ArrayList<>(sources.size());
        for (String d : sources.keySet()) {
            results.add(reload(d, force));
        }
        return results;
    }

    // ---------------------------------------------------------------- 内部

    private DomainRuntime loadBase() {
        DomainRuntime fresh = parseDomain(DomainRuntime.BASE);
        if (fresh == null) {
            // 兜底：空 base 运行时（K9，防启动失败）
            log.error("[INTENT] base 域解析失败，使用空运行时（K9 降级）");
            return DomainRuntime.empty(DomainRuntime.BASE);
        }
        return fresh;
    }

    /** 解析某域四份 YAML → 自洽校验 → DomainRuntime；失败返回 null（K9） */
    private DomainRuntime parseDomain(String domain) {
        DomainSource src = sources.get(domain);
        if (src == null) {
            return null;
        }
        Resource nerRes = resourceLoader.getResource(src.getNerDictionary());
        Resource termRes = resourceLoader.getResource(src.getTerminologyTable());
        Resource treeRes = resourceLoader.getResource(src.getIntentTree());
        Resource mapRes = resourceLoader.getResource(src.getMappingTable());

        var ner = nerParser.parse(nerRes);
        var terms = termParser.parse(termRes);
        var tree = treeParser.parse(treeRes);
        var mapping = mappingParser.parse(mapRes);

        // 自洽校验（5.3）：无空树（防误配清空线上规则）
        if (tree.allNodes().isEmpty()) {
            log.error("[INTENT] 自洽校验失败: domain={} 意图树为空", domain);
            return null;
        }
        long version = versions.computeIfAbsent(domain, k -> new AtomicLong(0)).get();
        return new DomainRuntime(domain, version, Instant.now(), ner, terms, tree, mapping);
    }

    private String nonNull(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private Object domainLock(String domain) {
        return domainLocks.computeIfAbsent(domain, k -> new Object());
    }

    private long versionOf(String domain) {
        AtomicLong v = versions.get(domain);
        return v == null ? 0L : v.get();
    }
}
