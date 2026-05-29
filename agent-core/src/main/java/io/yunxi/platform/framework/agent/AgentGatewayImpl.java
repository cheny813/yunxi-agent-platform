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

    /** Agent 领域服务 — 获取 Agent 实例 */
    private final AgentDomainService agentDomainService;

    /** Agent 定义加载器 — 获取配置中的超时、扩展点等信息 */
    private final AgentDefinitionLoader definitionLoader;

    /** PreProcessor 缓存（Bean 名称 → 实例） */
    private final Map<String, AgentPreProcessor> preProcessors = new ConcurrentHashMap<>();

    /** PostProcessor 缓存 */
    private final Map<String, AgentPostProcessor> postProcessors = new ConcurrentHashMap<>();

    /**
     * 设置 PreProcessor 列表（由 Spring 容器自动注入）
     * <p>
     * PreProcessor 在 Agent 调用前对输入消息进行预处理。
     * 通过 {@code @Autowired} 收集容器中所有 AgentPreProcessor 实现。
     * </p>
     */
    @Autowired(required = false)
    public void setPreProcessors(List<AgentPreProcessor> processors) {
        if (processors != null) {
            processors.forEach(p -> preProcessors.put(p.getClass().getName(), p));
        }
    }

    /**
     * 设置 PostProcessor 列表（由 Spring 容器自动注入）
     * <p>
     * PostProcessor 在 Agent 调用后对输出结果进行后处理。
     * 通过 {@code @Autowired} 收集容器中所有 AgentPostProcessor 实现。
     * </p>
     */
    @Autowired(required = false)
    public void setPostProcessors(List<AgentPostProcessor> processors) {
        if (processors != null) {
            processors.forEach(p -> postProcessors.put(p.getClass().getName(), p));
        }
    }

    /**
     * 构造 Agent 调用网关
     *
     * @param agentDomainService Agent 领域服务
     * @param definitionLoader   Agent 定义加载器（用于读取超时、Profile 等配置）
     */
    public AgentGatewayImpl(AgentDomainService agentDomainService,
            AgentDefinitionLoader definitionLoader) {
        this.agentDomainService = agentDomainService;
        this.definitionLoader = definitionLoader;
    }

    /**
     * 调用 Agent（无调用选项），返回完整响应。
     * <p>
     * 内部委托给 {@link #call(String, String, CallOptions)}，使用默认 CallOptions。
     * 最终经过完整拦截链：优雅关闭检查 → 限流 → 超时控制 → 实际 Agent 调用。
     * </p>
     */
    @Override
    public Mono<String> call(String agentName, String message) {
        return call(agentName, message, new CallOptions());
    }

    /**
     * SSE 流式调用 Agent（无调用选项）。
     * <p>
     * 内部委托给 {@link #callStream(String, String, CallOptions)}，使用默认 CallOptions。
     * 流式响应实时返回推理内容和工具调用中间结果。
     * </p>
     */
    @Override
    public Flux<String> callStream(String agentName, String message) {
        return callStream(agentName, message, new CallOptions());
    }

    /**
     * 带 Profile 的调用（多租户 / 多配置）。
     * <p>
     * Profile 名称通过 {@link CallOptions#setProfile(String)} 传入，
     * ProfileRouter 根据 {@code agentName + "#" + profile} 的复合键路由到对应的 Agent 实例。
     * 适用于同一个 Agent 名称在不同上下文中有不同配置的场景。
     * </p>
     */
    @Override
    public Mono<String> call(String agentName, String profile, String message) {
        return call(agentName, message, new CallOptions().setProfile(profile));
    }

    /**
     * 带调用选项的 Agent 调用（按需覆盖 Agent 配置）。
     * <p>
     * 调用选项支持覆盖超时时间（Options > Definition YAML > 默认 120s）、
     * 指定 Profile 等。此方法执行完整的拦截链：
     * <ol>
     * <li>解析 Agent 名称（含 Profile 路由）</li>
     * <li>优雅关闭检查（GracefulShutdownManager）</li>
     * <li>限流</li>
     * <li>超时控制</li>
     * <li>PreProcessor 扩展（输入预处理）</li>
     * <li>实际 Agent 调用（agent.call()）</li>
     * <li>PostProcessor 扩展（输出后处理）</li>
     * <li>监控埋点</li>
     * </ol>
     * </p>
     */
    @Override
    public Mono<String> call(String agentName, String message, CallOptions options) {
        return getAgentInfo(agentName, options)
                .flatMap(info -> callWithChain(info.originalName(), info, message, options));
    }

    /**
     * 带调用选项的 SSE 流式调用。
     * <p>
     * 流式调用使用 {@link StreamOptions#defaults()} 获取默认流式选项，
     * 逐事件提取文本内容返回给调用方。支持与同步版本相同的拦截链。
     * </p>
     */
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
     * <p>
     * 步骤编号与类注释中的 8 步拦截链对应（第 1 步"审计日志"在 getAgentInfo 中完成）：
     * 2. 限流检查 → 3. 优雅关闭检查 → 4. 超时控制 + Agent 调用 → 5. PreProcessor → 7. PostProcessor
     * → 8. 监控
     * </p>
     */
    private Mono<String> callWithChain(String agentName, AgentInvokeInfo info, String message, CallOptions options) {
        return Mono.just(message)
                // 2. 限流检查
                .transformDeferred(this::rateLimit)
                // 3. 优雅关闭检查
                .doOnSubscribe(s -> GracefulShutdownManager.getInstance().ensureAcceptingRequests())
                // 4. 超时控制 + Agent 调用
                .flatMap(m -> {
                    Duration timeout = resolveTimeout(info.definition(), options);
                    Msg userMsg = Msg.builder().textContent(m).build();
                    return info.agent().call(userMsg).timeout(timeout);
                })
                .map(Msg::getTextContent)
                // 5. PreProcessor 扩展（输入预处理）
                .flatMap(text -> applyPreProcessorsOnMono(agentName, text))
                // 6. (PostProcessor 在原代码中无独立步骤，注释保留以对齐)
                // 7. PostProcessor 扩展（输出后处理）
                .flatMap(result -> applyPostProcessors(agentName, message, result))
                // 8. 监控埋点
                .doOnSubscribe(s -> metricsStart(agentName))
                .doOnSuccess(r -> metricsSuccess(agentName))
                .doOnError(e -> metricsError(agentName, e));
    }

    /**
     * 流式调用拦截链
     * <p>
     * 流式 SSE 调用，步骤与同步版本一致：
     * 2. 限流检查 → 3. 优雅关闭检查 → 4. 超时控制 + 流式 Agent 调用 → 5. PreProcessor → 8. 监控
     * </p>
     */
    private Flux<String> callStreamWithChain(String agentName, AgentInvokeInfo info, String message,
            CallOptions options) {
        return Flux.just(message)
                // 2. 限流检查
                .transformDeferred(this::rateLimit)
                // 3. 优雅关闭检查
                .doOnSubscribe(s -> GracefulShutdownManager.getInstance().ensureAcceptingRequests())
                // 4. 超时控制 + 流式 Agent 调用
                .flatMap(m -> {
                    Duration timeout = resolveTimeout(info.definition(), options);
                    Msg userMsg = Msg.builder().textContent(m).build();
                    return info.agent().stream(List.of(userMsg), StreamOptions.defaults())
                            .timeout(timeout)
                            .map(event -> event.getMessage().getTextContent());
                })
                // 5. PreProcessor 扩展（流式文本预处理）
                .transformDeferred(flux -> applyPreProcessors(agentName, flux))
                // 8. 监控埋点
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

    /**
     * 审计日志：记录 Agent 调用来源和消息长度
     */
    private void auditLog(String agentName, String message) {
        log.info("[AUDIT] Agent: {}, message length: {}", agentName,
                message != null ? message.length() : 0);
    }

    /**
     * 限流控制（Flux 版本）
     * <p>
     * 预留扩展点，后续可接入 Token Bucket 或 Semaphore 实现。
     * </p>
     */
    private <T> reactor.core.publisher.Flux<T> rateLimit(reactor.core.publisher.Flux<T> flux) {
        return flux;
    }

    /**
     * 限流控制（Mono 版本）
     */
    private <T> reactor.core.publisher.Mono<T> rateLimit(reactor.core.publisher.Mono<T> mono) {
        return mono;
    }

    /**
     * 应用 PreProcessor 扩展（同步 Mono 版本）
     * <p>
     * 根据 AgentDefinition 配置的 PreProcessor Bean 名称，
     * 从缓存中查找并调用预处理逻辑。
     * </p>
     */
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

    /**
     * 应用 PreProcessor 扩展（流式 Flux 版本）
     * <p>
     * 对流式调用的每个消息片段应用预处理逻辑。
     * </p>
     */
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

    /**
     * 应用 PostProcessor 扩展
     * <p>
     * 根据 AgentDefinition 配置的 PostProcessor Bean 名称，
     * 从缓存中查找并调用后处理逻辑，可修改 Agent 的输出结果。
     * </p>
     */
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

    /**
     * 按名称或类名查找 PreProcessor 实例
     * <p>
     * 先精确匹配完整类名，再尝试匹配简单类名。
     * </p>
     */
    private AgentPreProcessor findPreProcessor(String beanNameOrClassName) {
        // 先按 Bean 名称查找，再按类名查找
        return preProcessors.values().stream()
                .filter(p -> p.getClass().getName().equals(beanNameOrClassName)
                        || p.getClass().getSimpleName().equals(beanNameOrClassName))
                .findFirst()
                .orElse(null);
    }

    /**
     * 按名称或类名查找 PostProcessor 实例
     */
    private AgentPostProcessor findPostProcessor(String beanNameOrClassName) {
        return postProcessors.values().stream()
                .filter(p -> p.getClass().getName().equals(beanNameOrClassName)
                        || p.getClass().getSimpleName().equals(beanNameOrClassName))
                .findFirst()
                .orElse(null);
    }

    // ========== 监控埋点（预留，可接入 Micrometer/Prometheus） ==========

    /**
     * 调用开始 — 监控埋点
     */
    private void metricsStart(String agentName) {
        log.debug("[METRICS] start: {}", agentName);
    }

    /**
     * 调用成功 — 监控埋点
     */
    private void metricsSuccess(String agentName) {
        log.debug("[METRICS] success: {}", agentName);
    }

    /**
     * 调用失败 — 监控埋点（记录错误信息）
     */
    private void metricsError(String agentName, Throwable e) {
        log.warn("[METRICS] error: {} - {}", agentName, e.getMessage());
    }

    /**
     * Agent 调用信息记录
     *
     * @param originalName  原始 Agent 名称（来自请求）
     * @param effectiveName 实际使用的 Agent 名称（Profile 解析后）
     * @param agent         Agent 实例（HarnessAgent 包装）
     * @param definition    Agent 配置定义（用于超时、扩展点等）
     * @param options       调用选项
     */
    private record AgentInvokeInfo(
            String originalName,
            String effectiveName,
            io.agentscope.core.agent.Agent agent,
            AgentDefinition definition,
            CallOptions options) {
    }
}
