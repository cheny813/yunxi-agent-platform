package io.yunxi.platform.intent.reload;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.yunxi.platform.intent.domain.DomainRegistry;
import io.yunxi.platform.intent.domain.DomainRuntime;
import io.yunxi.platform.intent.domain.ReloadResult;

/**
 * 意图域热更新门面。
 *
 * <p>封装 {@link DomainRegistry} 的 reload/reloadAll：幂等串行（域粒度锁由 registry
 * 内置）、自洽校验（registry 内置）、审计日志（域 / 源文件 / 节点数 / 词条数 / 耗时 /
 * 新版本号 / 操作人标识）与监听器广播（{@link IntentReloadListener} 预留）。
 * 供 actuator 端点（{@link IntentEndpoint}）与未来配置中心事件调用。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Component
public class IntentReloader {

    private static final Logger log = LoggerFactory.getLogger(IntentReloader.class);

    private final DomainRegistry registry;
    private final List<IntentReloadListener> listeners;

    public IntentReloader(DomainRegistry registry, List<IntentReloadListener> listeners) {
        this.registry = registry;
        this.listeners = listeners == null ? List.of() : listeners;
    }

    /** 单域热更新；未知域 / 解析失败由 registry 返回失败结果（不抛出） */
    public ReloadResult reload(String domain, boolean force) {
        long start = System.currentTimeMillis();
        ReloadResult result = registry.reload(domain, force);
        audit(result, start);
        broadcast(result);
        return result;
    }

    /** 全量热更新（每域结果逐条返回，含 base 与全部业务域） */
    public List<ReloadResult> reloadAll(boolean force) {
        long start = System.currentTimeMillis();
        List<ReloadResult> results = registry.reloadAll(force);
        for (ReloadResult r : results) {
            audit(r, start);
            broadcast(r);
        }
        return results;
    }

    /** 各域源文件位置（透传 registry，供文件轮询器检测 mtime） */
    public Map<String, List<String>> sourceLocations() {
        return registry.sourceLocations();
    }

    private void audit(ReloadResult r, long start) {
        long cost = System.currentTimeMillis() - start;
        String operator = operator();
        if (r.success()) {
            String stat = snapshotStat(r.domain());
            log.info("[INTENT] 热更新成功 domain={} version={} {} cost={}ms operator={}",
                    r.domain(), r.version(), stat, cost, operator);
        } else {
            log.warn("[INTENT] 热更新失败 domain={} version={} reason={} cost={}ms operator={}",
                    r.domain(), r.version(), r.reason(), cost, operator);
        }
    }

    /** 更新后运行时统计（节点 / NER 词条 / 术语 / 映射条数），未知域返回空串 */
    private String snapshotStat(String domain) {
        DomainRuntime rt = registry.runtime(domain);
        if (rt == null || rt.tree() == null) {
            return "runtime=null";
        }
        int nodes = rt.tree().allNodes().size();
        int entities = rt.ner() == null ? 0 : rt.ner().entries().size();
        int terms = rt.terms() == null ? 0 : rt.terms().terms().size();
        int mappings = rt.mapping() == null ? 0 : rt.mapping().mappings().size();
        return String.format("nodes=%d,entities=%d,terms=%d,mappings=%d", nodes, entities, terms, mappings);
    }

    private void broadcast(ReloadResult r) {
        for (IntentReloadListener listener : listeners) {
            try {
                listener.onReload(r);
            } catch (Exception e) {
                log.warn("[INTENT] 热更新监听器异常（不影响主流程）: {}", e.getMessage());
            }
        }
    }

    /** 操作人标识（预留：可从 SecurityContext / 请求头透传，当前固定 actuator 来源） */
    private String operator() {
        return "actuator";
    }
}
