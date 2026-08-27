package io.yunxi.platform.intent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.yunxi.platform.intent.classify.IntentClassifier;
import io.yunxi.platform.intent.config.IntentProperties;
import io.yunxi.platform.intent.domain.DomainRegistry;
import io.yunxi.platform.intent.domain.DomainResolver;
import io.yunxi.platform.intent.domain.DomainRuntime;
import io.yunxi.platform.intent.mapping.IntentMappingTable;
import io.yunxi.platform.intent.ner.NerStage;
import io.yunxi.platform.intent.rewrite.RewriteProcessor;

/**
 * 意图引擎门面。
 *
 * <p>Stage 0 领域选择 → Stage 1 NER → Stage 2 改写 → Stage 3 分类 → Stage 4 映射。
 * Stage 0 由 {@link DomainResolver} 解析目标域，{@link DomainRegistry} 取合并后
 * {@link DomainRuntime} 传入各 Stage（快照是唯一数据源）。</p>
 *
 * <p>安全原则：任何阶段异常均捕获降级，永不向上抛出；总开关关闭时退化为仅场景模式
 * （行为等价旧 SceneDetectionService）。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Service
public class DefaultIntentEngine implements IntentEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultIntentEngine.class);

    private final IntentProperties props;
    private final DomainResolver domainResolver;
    private final DomainRegistry domainRegistry;
    private final NerStage nerStage;
    private final IntentClassifier classifier;
    private final IntentMappingTable mappingTable;
    private final Map<String, RewriteProcessor> processorsByName;

    public DefaultIntentEngine(IntentProperties props,
            DomainResolver domainResolver,
            DomainRegistry domainRegistry,
            NerStage nerStage,
            IntentClassifier classifier,
            IntentMappingTable mappingTable,
            List<RewriteProcessor> processors) {
        this.props = props;
        this.domainResolver = domainResolver;
        this.domainRegistry = domainRegistry;
        this.nerStage = nerStage;
        this.classifier = classifier;
        this.mappingTable = mappingTable;
        Map<String, RewriteProcessor> map = new HashMap<>();
        for (RewriteProcessor p : processors) {
            map.put(p.name(), p);
        }
        this.processorsByName = Map.copyOf(map);
    }

    @Override
    public IntentResult analyze(IntentContext ctx) {
        long t0 = System.nanoTime();
        try {
            if (ctx == null || ctx.query() == null || ctx.query().isBlank()) {
                return IntentResult.sceneOnly("", "GENERAL");
            }
            // K2：关闭 = 仅场景模式
            if (!props.isEnabled()) {
                return IntentResult.sceneOnly(ctx.query(), classifier.detectSceneName(ctx.query()));
            }

            boolean degraded = false;

            // Stage 0 领域选择
            long s0 = System.nanoTime();
            String domain;
            DomainRuntime runtime;
            try {
                domain = domainResolver.resolve(ctx);
                runtime = domainRegistry.runtime(domain);
            } catch (Exception e) {
                log.warn("[INTENT] 领域解析异常，回落 base: {}", e.getMessage());
                domain = "base";
                runtime = domainRegistry.runtime("base");
                degraded = true;
            }
            long resolveMs = ms(s0);

            // Stage 1 NER
            long s1 = System.nanoTime();
            List<Entity> entities;
            try {
                entities = nerStage.extract(ctx.query(), runtime);
            } catch (Exception e) {
                log.warn("[INTENT] NER 阶段异常，降级为空: {}", e.getMessage());
                entities = List.of();
                degraded = true;
            }
            long nerMs = ms(s1);

            // Stage 2 改写（仅 terminology）
            long s2 = System.nanoTime();
            String rewritten = ctx.query();
            if (props.isRewriteEnabled()) {
                for (String name : props.getRewriteProcessors()) {
                    RewriteProcessor p = processorsByName.get(name);
                    if (p == null) {
                        log.warn("[INTENT] 改写处理器 '{}' 未注册，跳过", name);
                        continue;
                    }
                    try {
                        String r = p.process(rewritten,
                                new RewriteProcessor.RewriteContext(ctx.recentMessages(), entities, runtime));
                        if (r != null) {
                            rewritten = r;
                        }
                    } catch (Exception e) {
                        log.warn("[INTENT] 改写处理器 '{}' 异常: {}", name, e.getMessage());
                        degraded = true;
                    }
                }
            }
            long rewriteMs = ms(s2);

            // Stage 3 分类（三级链仅此一次；sceneName 同时作为 IntentResult.sceneName 来源）
            // 使用改写后的 rewritten 作为分类输入：Stage 2 术语归一（如 一周→一个星期）
            // 必须先于关键词匹配生效，否则改写对分类无意义
            long s3 = System.nanoTime();
            Intent intent;
            String sceneName;
            try {
                sceneName = classifier.detectSceneName(rewritten);
                intent = classifier.classify(rewritten, entities, sceneName, runtime);
            } catch (Exception e) {
                log.warn("[INTENT] 分类阶段异常，降级 unknown: {}", e.getMessage());
                intent = Intent.unknown();
                sceneName = "GENERAL";
                degraded = true;
            }
            long classifyMs = ms(s3);

            // Stage 4 映射
            long s4 = System.nanoTime();
            RouteHint hint;
            try {
                hint = mappingTable.routeFor(intent.intentId(), intent.confidence(), runtime);
            } catch (Exception e) {
                log.warn("[INTENT] 映射阶段异常，降级空路由: {}", e.getMessage());
                hint = RouteHint.empty();
                degraded = true;
            }
            long mappingMs = ms(s4);

            IntentResult result = new IntentResult(ctx.query(), rewritten, entities, intent,
                    hint, sceneName, domain,
                    new StageTimings(nerMs, rewriteMs, classifyMs, mappingMs, ms(t0)), degraded);
            log.info("[INTENT] domain={}, agent={}, intent={}({} via {}), scene={}, entities={}, "
                            + "rewritten={}, routeHint={}, degraded={}, timings=resolve={}ms ner={}ms "
                            + "rewrite={}ms classify={}ms mapping={}ms total={}ms",
                    domain, ctx.agentName(), intent.intentId(), intent.label(), intent.matchedBy(),
                    sceneName, entities.size(), rewritten, hint.isEmpty() ? "-" : hint.suggestedAgent(),
                    degraded, resolveMs, nerMs, rewriteMs, classifyMs, mappingMs, ms(t0));
            return result;
        } catch (Exception e) {
            log.warn("[INTENT] 意图分析整体异常，降级: {}", e.getMessage());
            return IntentResult.sceneOnly(ctx != null ? ctx.query() : "", "GENERAL");
        }
    }

    private long ms(long from) {
        return (System.nanoTime() - from) / 1_000_000L;
    }
}
