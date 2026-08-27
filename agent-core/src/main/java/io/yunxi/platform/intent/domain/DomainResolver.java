package io.yunxi.platform.intent.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.yunxi.platform.intent.IntentContext;
import io.yunxi.platform.intent.config.IntentProperties;
import io.yunxi.platform.intent.config.IntentProperties.Resolver;
import io.yunxi.platform.intent.config.IntentProperties.Resolver.Rule;

/**
 * 领域解析器。
 *
 * <p>判定链四层：
 * <pre>
 * 显式 domain（ctx.domain 非空，信任调用方）
 *   ─▶ 规则匹配（agent 前缀数组 / profile 归属，按规则序）
 *     ─▶ default 域（resolver.default-domain）
 *       ─▶ base（单域模式兜底）
 * </pre>
 * 规则命中但域未注册 → warn 并继续下一层（防误配路由到空域）。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Component
public class DomainResolver {

    private static final Logger log = LoggerFactory.getLogger(DomainResolver.class);

    private final IntentProperties properties;
    private final DomainRegistry registry;

    public DomainResolver(IntentProperties properties, DomainRegistry registry) {
        this.properties = properties;
        this.registry = registry;
    }

    /**
     * 解析目标域。
     *
     * @param ctx 意图上下文（domain 可 null；agentName/profile 参与规则匹配）
     * @return 域名（永不 null，未命中回落 base）
     */
    public String resolve(IntentContext ctx) {
        // 1. 显式 domain（最高优先级）
        if (ctx.domain() != null && !ctx.domain().isBlank()) {
            String d = ctx.domain();
            if (registry.domains().contains(d)) {
                return d;
            }
            log.warn("[INTENT] 显式 domain 未注册: {}，回落规则链", d);
        }

        // 2. 规则匹配（agent 前缀 / profile 归属，按规则序）
        Resolver cfg = properties.getResolver();
        if (cfg != null && cfg.getRules() != null) {
            for (Rule rule : cfg.getRules()) {
                if (rule.getDomain() == null || rule.getDomain().isBlank()) {
                    continue;
                }
                boolean hit = matchRule(rule, ctx);
                if (hit) {
                    if (registry.domains().contains(rule.getDomain())) {
                        return rule.getDomain();
                    }
                    log.warn("[INTENT] 规则命中但域未注册: rule={}, domain={}，继续下一层",
                            rule.getName(), rule.getDomain());
                }
            }
        }

        // 3. default 域
        String defaultDomain = cfg != null && cfg.getDefaultDomain() != null
                ? cfg.getDefaultDomain() : DomainRuntime.BASE;
        if (registry.domains().contains(defaultDomain)) {
            return defaultDomain;
        }

        // 4. base 兜底
        return DomainRuntime.BASE;
    }

    private boolean matchRule(Rule rule, IntentContext ctx) {
        // agent 前缀
        if (rule.getAgentPrefixes() != null && ctx.agentName() != null) {
            for (String prefix : rule.getAgentPrefixes()) {
                if (prefix != null && !prefix.isBlank() && ctx.agentName().startsWith(prefix)) {
                    return true;
                }
            }
        }
        // profile 归属（流式入口 request.getProfile()；非流式传 null）
        if (rule.getProfileIn() != null && ctx.profile() != null) {
            for (String p : rule.getProfileIn()) {
                if (p != null && p.equalsIgnoreCase(ctx.profile())) {
                    return true;
                }
            }
        }
        return false;
    }
}
