package io.yunxi.platform.framework.agent;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.yunxi.platform.framework.agent.extension.AgentPostProcessor;
import io.yunxi.platform.framework.agent.extension.AgentPreProcessor;
import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.config.AgentDefinitionLoader;
import io.yunxi.platform.shared.exception.AgentNotFoundException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * AgentGateway 实现 — 统一拦截链
 * <p>
 * 所有经过 AgentGateway 的调用都会依次经过：
 * 1. 审计日志
 * 2. 限流检查
 * 3. 优雅关闭检查（GracefulShutdownManager）
 * 4. 超时控制
 * 5. PreProcessor 扩展点
 * 6. 实际 Agent 调用
 * 7. PostProcessor 扩展点
 * 8. 监控埋点
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class AgentGatewayImpl implements AgentGateway {

    private static final Logger log = LoggerFactory.getLogger(AgentGatewayImpl.class);

    private final AgentDomainService agentDomainService;
    private final AgentDefinitionLoader definitionLoader;

    /** PreProcessor 缓存（Bean 名称 → 实例） */
    private final Map<String, AgentPreProcessor> preProcessors = new ConcurrentHashMap<>();

    /** PostProcessor 缓存 */
    private final Map<String, AgentPostProcessor> postProcessors = new ConcurrentHashMap<>();

    @Autowired(required = false)
    public void setPreProcessors(List<AgentPreProcessor> processors) {
        if (processors != null) {
            processors.forEach(p -> preProcessors.put(p.getClass().getName(), p));
        }
    }

    @Autowired(required = false)
    public void setPostProcessors(List<AgentPostProcessor> processors) {
        if (processors != null) {
            processors.forEach(p -> postProcessors.put(p.getClass().getName(), p));
        }
    }

    public AgentGatewayImpl(AgentDomainService agentDomainService,
            AgentDefinitionLoader definitionLoader) {
        this.agentDomainService = agentDomainService;
        this.definitionLoader = definitionLoader;
    }

    @Override
    public Mono<String> call(String agentName, String message) {
        return call(agentName, message, new CallOptions());
    }

    @Override
    public Flux<String> callStream(String agentName, String message) {
        return callStream(agentName, message, new CallOptions());
    }

    @Override
    public Mono<String> call(String agentName, String profile, String message) {
        return call(agentName, message, new CallOptions().setProfile(profile));
    }

    @Override
    public Mono<String> call(String agentName, String message, CallOptions options) {
        return getAgentInfo(agentName, options)
                .flatMap(info -> callWithChain(info.originalName(), info, message, options));
    }

    @Override
    public Flux<String> callStream(String agentName, String message, CallOptions options) {
        return getAgentInfo(agentName, options)
                .flux()
                .flatMap(info -> callStreamWithChain(info.originalName(), info, message, options));
    }

    /**
     * 获取 Agent 信息（带 Profile 解析）
     */
    private Mono<AgentInvokeInfo> getAgentInfo(String agentName, CallOptions options) {
        return Mono.fromCallable(() -> {
            String effectiveName = resolveAgentName(agentName, options);
            var agent = agentDomainService.getAgentInstance(effectiveName);
            var def = definitionLoader.getAgentDefinition(agentName);
            return new AgentInvokeInfo(agentName, effectiveName, agent, def, options);
        }).onErrorMap(AgentNotFoundException.class, e -> e);
    }

    /**
     * 同步调用拦截链
     */
    private Mono<String> callWithChain(String agentName, AgentInvokeInfo info, String message, CallOptions options) {
        return Mono.just(message)
                // 1. 优雅关闭检查
                .doOnSubscribe(s -> GracefulShutdownManager.getInstance().ensureAcceptingRequests())
                // 2. 限流
                .transformDeferred(this::rateLimit)
                // 3. 超时控制
                .flatMap(m -> {
                    Duration timeout = resolveTimeout(info.definition(), options);
                    Msg userMsg = Msg.builder().textContent(m).build();
                    return info.agent().call(userMsg).timeout(timeout);
                })
                .map(Msg::getTextContent)
                // 4. PreProcessor 扩展
                .flatMap(text -> applyPreProcessorsOnMono(agentName, text))
                // 5. PostProcessor 扩展
                .flatMap(result -> applyPostProcessors(agentName, message, result))
                // 6. 监控埋点
                .doOnSubscribe(s -> metricsStart(agentName))
                .doOnSuccess(r -> metricsSuccess(agentName))
                .doOnError(e -> metricsError(agentName, e));
    }

    /**
     * 流式调用拦截链
     */
    private Flux<String> callStreamWithChain(String agentName, AgentInvokeInfo info, String message,
            CallOptions options) {
        return Flux.just(message)
                .doOnSubscribe(s -> GracefulShutdownManager.getInstance().ensureAcceptingRequests())
                .transformDeferred(this::rateLimit)
                .flatMap(m -> {
                    Duration timeout = resolveTimeout(info.definition(), options);
                    Msg userMsg = Msg.builder().textContent(m).build();
                    return info.agent().stream(List.of(userMsg), StreamOptions.defaults())
                            .timeout(timeout)
                            .map(event -> event.getMessage().getTextContent());
                })
                .transformDeferred(flux -> applyPreProcessors(agentName, flux))
                .doOnSubscribe(s -> metricsStart(agentName))
                .doOnComplete(() -> metricsSuccess(agentName))
                .doOnError(e -> metricsError(agentName, e));
    }

    /**
     * 解析最终使用的 Agent 名称（支持 Profile）
     */
    private String resolveAgentName(String agentName, CallOptions options) {
        String profile = options != null ? options.getProfile() : null;
        if (profile != null && !profile.isBlank()) {
            return agentName + "#" + profile;
        }
        return agentName;
    }

    /**
     * 解析超时时间：Options > Definition > 默认 120s
     */
    private Duration resolveTimeout(AgentDefinition def, CallOptions options) {
        if (options != null && options.getTimeout() != null) {
            return options.getTimeout();
        }
        if (def != null && def.getRuntime() != null) {
            return def.getRuntime().getTimeout();
        }
        return Duration.ofSeconds(120);
    }

    // ========== 统一治理 ==========

    private void auditLog(String agentName, String message) {
        log.info("[AUDIT] Agent: {}, message length: {}", agentName,
                message != null ? message.length() : 0);
    }

    private <T> reactor.core.publisher.Flux<T> rateLimit(reactor.core.publisher.Flux<T> flux) {
        // 预留：具体限流实现（如 Token Bucket、Semaphore）
        return flux;
    }

    private <T> reactor.core.publisher.Mono<T> rateLimit(reactor.core.publisher.Mono<T> mono) {
        // 预留：具体限流实现（如 Token Bucket、Semaphore）
        return mono;
    }

    private Mono<String> applyPreProcessorsOnMono(String agentName, String text) {
        AgentDefinition def = definitionLoader.getAgentDefinition(agentName);
        if (def == null || def.getExtensions() == null) {
            return Mono.just(text);
        }
        String preBeanName = def.getExtensions().getPreProcessor();
        if (preBeanName == null || preBeanName.isBlank()) {
            return Mono.just(text);
        }
        AgentPreProcessor processor = findPreProcessor(preBeanName);
        if (processor == null) {
            log.warn("PreProcessor [{}] not found for agent [{}]", preBeanName, agentName);
            return Mono.just(text);
        }
        return processor.preProcess(agentName, text);
    }

    private Flux<String> applyPreProcessors(String agentName, Flux<String> flux) {
        AgentDefinition def = definitionLoader.getAgentDefinition(agentName);
        if (def == null || def.getExtensions() == null) {
            return flux;
        }
        String preBeanName = def.getExtensions().getPreProcessor();
        if (preBeanName == null || preBeanName.isBlank()) {
            return flux;
        }
        // 查找已注册的 PreProcessor
        AgentPreProcessor processor = findPreProcessor(preBeanName);
        if (processor == null) {
            log.warn("PreProcessor [{}] not found for agent [{}]", preBeanName, agentName);
            return flux;
        }
        return flux.flatMap(msg -> processor.preProcess(agentName, msg));
    }

    private Mono<String> applyPostProcessors(String agentName, String input, String output) {
        AgentDefinition def = definitionLoader.getAgentDefinition(agentName);
        if (def == null || def.getExtensions() == null) {
            return Mono.just(output);
        }
        String postBeanName = def.getExtensions().getPostProcessor();
        if (postBeanName == null || postBeanName.isBlank()) {
            return Mono.just(output);
        }
        AgentPostProcessor processor = findPostProcessor(postBeanName);
        if (processor == null) {
            return Mono.just(output);
        }
        return processor.postProcess(agentName, input, output);
    }

    private AgentPreProcessor findPreProcessor(String beanNameOrClassName) {
        // 先按 Bean 名称查找，再按类名查找
        return preProcessors.values().stream()
                .filter(p -> p.getClass().getName().equals(beanNameOrClassName)
                        || p.getClass().getSimpleName().equals(beanNameOrClassName))
                .findFirst()
                .orElse(null);
    }

    private AgentPostProcessor findPostProcessor(String beanNameOrClassName) {
        return postProcessors.values().stream()
                .filter(p -> p.getClass().getName().equals(beanNameOrClassName)
                        || p.getClass().getSimpleName().equals(beanNameOrClassName))
                .findFirst()
                .orElse(null);
    }

    // ========== 监控埋点（预留） ==========

    private void metricsStart(String agentName) {
        log.debug("[METRICS] start: {}", agentName);
    }

    private void metricsSuccess(String agentName) {
        log.debug("[METRICS] success: {}", agentName);
    }

    private void metricsError(String agentName, Throwable e) {
        log.warn("[METRICS] error: {} - {}", agentName, e.getMessage());
    }

    /**
     * Agent 调用信息记录
     */
    private record AgentInvokeInfo(
            String originalName,
            String effectiveName,
            io.agentscope.core.ReActAgent agent,
            AgentDefinition definition,
            CallOptions options) {
    }
}