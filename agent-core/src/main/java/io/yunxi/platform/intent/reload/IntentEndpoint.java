package io.yunxi.platform.intent.reload;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import io.yunxi.platform.intent.Intent;
import io.yunxi.platform.intent.classify.HybridIntentClassifier;
import io.yunxi.platform.intent.domain.DomainRegistry;
import io.yunxi.platform.intent.domain.DomainRuntime;
import io.yunxi.platform.intent.domain.ReloadResult;

/**
 * 意图引擎管理端点（actuator {@code /actuator/intent/*}）。
 *
 * <p>三个端点：</p>
 * <ul>
 *   <li>{@code GET /actuator/intent/status} —— 各域快照信息（版本 / 加载时间 /
 *       节点数 / NER 词条数 / 术语数 / 映射数）；</li>
 *   <li>{@code POST /actuator/intent/reload?domain=xx&mode=force} —— 热更新
 *       （无 domain 参数为全量；mode=force 强制重建，缺省复用 registry 缓存语义）；</li>
 *   <li>{@code GET /actuator/intent/suggest-words?domain=xx&topN=10} —— LLM 热度建议词
 *       （mode=rule 未装配 LLM 通道时优雅降级返回 enabled=false）。</li>
 * </ul>
 *
 * <p>写操作未注解参数从查询串绑定（Spring Boot actuator 标准行为），
 * 即 reload 走 {@code POST /actuator/intent/reload?domain=nutrition&mode=force}。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Component
@Endpoint(id = "intent")
public class IntentEndpoint {

    private final DomainRegistry registry;
    private final IntentReloader reloader;
    private final ObjectProvider<HybridIntentClassifier> hybridProvider;

    public IntentEndpoint(DomainRegistry registry, IntentReloader reloader,
            ObjectProvider<HybridIntentClassifier> hybridProvider) {
        this.registry = registry;
        this.reloader = reloader;
        this.hybridProvider = hybridProvider;
    }

    /** GET /actuator/intent/status —— 各域快照信息 */
    @ReadOperation
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String domain : registry.domains()) {
            DomainRuntime rt = registry.runtime(domain);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("version", rt.version());
            info.put("loadedAt", rt.loadedAt() == null ? null : rt.loadedAt().toString());
            info.put("nodes", rt.tree() == null ? 0 : rt.tree().allNodes().size());
            info.put("entities", rt.ner() == null ? 0 : rt.ner().entries().size());
            info.put("terms", rt.terms() == null ? 0 : rt.terms().terms().size());
            info.put("mappings", rt.mapping() == null ? 0 : rt.mapping().mappings().size());
            out.put(domain, info);
        }
        return out;
    }

    /** POST /actuator/intent/reload —— 热更新（无 domain=全量；mode=force 强制重建） */
    @WriteOperation
    public List<ReloadResult> reload(String domain, String mode) {
        boolean force = "force".equalsIgnoreCase(mode);
        if (domain == null || domain.isBlank()) {
            return reloader.reloadAll(force);
        }
        return List.of(reloader.reload(domain, force));
    }

    /** GET /actuator/intent/suggest-words —— LLM 热度建议词（按域过滤，可空） */
    @ReadOperation
    public Map<String, Object> suggestWords(String domain, Integer topN) {
        int n = topN == null ? 10 : Math.max(1, topN);
        HybridIntentClassifier hybrid = hybridProvider.getIfAvailable();
        if (hybrid == null) {
            return Map.of(
                    "enabled", false,
                    "message", "classification.mode=rule，LLM 通道未装配",
                    "suggestions", List.of());
        }
        List<Intent> suggestions = hybrid.hotSuggestions(domain, n);
        return Map.of(
                "enabled", true,
                "domain", domain == null || domain.isBlank() ? "" : domain,
                "suggestions", suggestions);
    }
}
