package io.yunxi.app;

import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.core.middleware.MiddlewareBase;
import io.yunxi.capability.ConfigurableModelRoutingPolicy;
import io.yunxi.capability.ModelProperties;
import io.yunxi.capability.InMemoryMcpDirectory;
import io.yunxi.capability.ModelRoutingPolicy;
import io.yunxi.capability.McpDirectory;
import io.yunxi.capability.RoutedModel;
import io.yunxi.capability.FineRankingScorer;
import io.yunxi.capability.ModelResolver;
import io.yunxi.capability.RuleBasedFineRankingScorer;
import io.yunxi.capability.Telev1FineRankingScorer;
import io.yunxi.config.ConfigCenter;
import io.yunxi.config.InMemoryConfigCenter;
import io.yunxi.definition.AgentSpecValidator;
import io.yunxi.definition.DefaultAgentSpecValidator;
import io.yunxi.fabric.InMemoryDistributedRuntimeConfig;
import io.yunxi.fabric.CrossInstanceEventBus;
import io.yunxi.fabric.RedisDistributedRuntimeConfig;
import io.yunxi.fabric.RedisMessageBus;
import io.yunxi.ingress.ConversationController;
import io.yunxi.ingress.IngressGateway;
import io.yunxi.ingress.ProtocolAdapter;
import io.yunxi.ingress.WebProtocolAdapter;
import io.yunxi.app.mcp.McpNacosProperties;
import io.yunxi.app.config.ConfigCenterNacosProperties;
import io.yunxi.app.config.NacosBackedConfigCenter;
import io.yunxi.app.config.TenantProperties;
import io.yunxi.app.config.TenantQuotaProperties;
import io.yunxi.app.shard.AgentShardResolver;
import io.yunxi.app.shard.AgentForwardingRouter;
import io.yunxi.app.shard.AllOwnsShardResolver;
import io.yunxi.ingress.ShardForwarder;
import io.yunxi.app.tenant.TenantQuotaMiddleware;
import io.yunxi.runtime.tenant.HeaderTenantResolver;
import io.yunxi.runtime.tenant.TenantConfig;
import io.yunxi.runtime.tenant.TenantLifecycleService;
import io.yunxi.runtime.tenant.TenantResolver;
import io.yunxi.runtime.TenantAgentAllocationService;
import io.yunxi.intelligence.IntentEngine;
import io.yunxi.intelligence.IntentEngineConfiguration;
import io.yunxi.observability.AuditSink;
import io.yunxi.observability.InMemoryAuditSink;
import io.yunxi.observability.InMemoryMetricRegistry;
import io.yunxi.observability.InMemoryTraceStore;
import io.yunxi.observability.MetricRegistry;
import io.yunxi.observability.TraceStore;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import io.agentscope.spring.boot.agui.common.AguiProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.yunxi.runtime.AgentBuildContributor;
import io.yunxi.runtime.AgentConfigurer;
import io.yunxi.runtime.MemoryProperties;
import io.yunxi.runtime.rag.RagToolRegistrar;
import io.yunxi.runtime.AgentDefinitionLoader;
import io.yunxi.runtime.AgentLookup;
import io.yunxi.runtime.DefaultExecutionFacade;
import io.yunxi.runtime.DistributedRuntimeConfig;
import io.yunxi.runtime.ExecutionFacade;
import io.agentscope.core.model.ModelRegistry;
import io.yunxi.runtime.ModelFactory;
import io.yunxi.runtime.MuseToolRegistrar;
import io.yunxi.runtime.ProtocolUserIdResolver;
import io.agentscope.harness.agent.gateway.HarnessGateway;
import io.yunxi.app.im.YunxiChannelRuntimeContextResolver;
import io.yunxi.runtime.middleware.ChatAuditMiddleware;
import io.yunxi.runtime.middleware.HitlMiddleware;
import io.yunxi.runtime.middleware.OtelTracingMiddleware;
import io.yunxi.runtime.middleware.ResilienceMiddleware;
import io.yunxi.runtime.middleware.SessionToolGroupActivationMiddleware;
import io.yunxi.runtime.middleware.SessionToolGroupRegistry;
import io.yunxi.runtime.permission.PermissionTemplateResolver;
import io.yunxi.app.startup.NoOpStartupGate;
import io.yunxi.app.startup.RedisStartupGate;
import io.yunxi.app.startup.StartupGate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 应用平面。运行时核心装配。这里是整个目标架构装配图的唯一汇聚点：
 * 所有平面（定义 / 配置 / 可观测 / 能力 / 运行时 / 入口 / 织面）的默认实现在此被装配为 Spring Bean，
 * 并由 {@link AgentConfigurer} 这个唯一运行时装配点统一构建 Agent。
 *
 * <p>Agent 定义全走 {@link ConfigCenter}（统一真相源，支持运行时热加载）；装配后的 Agent 注册进 AgentScope-Java
 * 框架协议层 {@link AguiAgentRegistry}（进程内单例 Agent 真相源），经 {@link AgentLookup} 供执行门面与各协议读取，
 * yunxi 不维护第二份 Agent 注册表。</p>
 *
 * <p>本配置只承接默认（内存 / 单机）实现；生产形态下的 Redis 后端、aistio 接入、真实模型工厂等通过覆盖对应 Bean 注入。</p>
 */
@Configuration
@Import({IntentEngineConfiguration.class})
@EnableScheduling
@EnableConfigurationProperties({ModelProperties.class, McpNacosProperties.class, MemoryProperties.class, ConfigCenterNacosProperties.class, TenantProperties.class, TenantQuotaProperties.class})
public class RuntimeConfiguration {

    /**
     * 配置中心单一接线点：opt-in 切换后端。
     * 仅当 {@code yunxi.config.nacos.enabled=true} 时启用 Nacos 后端（多实例共享 Agent 定义真相源）；
     * 否则降级为默认内存版。两者通过 ConditionalOnMissingBean 互斥，保证全局唯一 ConfigCenter Bean。
     */
    @Bean
    @ConditionalOnProperty(name = "yunxi.config.nacos.enabled", havingValue = "true")
    public ConfigCenter nacosConfigCenter(ConfigCenterNacosProperties props,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        // 复用平台全局 RedisConnectionFactory（StringRedisTemplate 由 spring-boot-starter-data-redis 自动配置）；
        // 未配置 Redis 时 getIfAvailable() 返回 null，NacosBackedConfigCenter 自动退化为纯 Nacos + 内存读缓存。
        return new NacosBackedConfigCenter(props, redisTemplateProvider);
    }

    @Bean
    @ConditionalOnMissingBean(ConfigCenter.class)
    public ConfigCenter inMemoryConfigCenter() {
        return new InMemoryConfigCenter();
    }

    /** 默认内存版 MCP 目录。跨 Agent 共享底层 MCP 客户端连接（clientCache），消灭 N×S 连接爆炸。 */
    @Bean
    public McpDirectory mcpDirectory(
            @Value("${yunxi.mcp.client-cache.enabled:true}") boolean clientCacheEnabled,
            @Value("${yunxi.mcp.client-cache.evict-on-unregister:true}") boolean evictOnUnregister) {
        return new InMemoryMcpDirectory(clientCacheEnabled, evictOnUnregister);
    }

    /** 跨实例启动协调闸门：并发启动时仅 leader 实例全量布建，降低启动风暴。 */
    @Bean
    @ConditionalOnProperty(name = "yunxi.startup-gate.enabled", havingValue = "true", matchIfMissing = true)
    public StartupGate startupGate(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${yunxi.startup-gate.lock-key:yunxi:startup-gate:leader}") String lockKey,
            @Value("${yunxi.startup-gate.allow-concurrent-leaders:1}") int allowConcurrentLeaders,
            @Value("${yunxi.startup-gate.leader-lock-ttl-seconds:30}") int ttlSeconds) {
        return new RedisStartupGate(
                redisTemplateProvider, lockKey, allowConcurrentLeaders, Duration.ofSeconds(ttlSeconds));
    }

    @Bean
    @ConditionalOnProperty(name = "yunxi.startup-gate.enabled", havingValue = "false")
    public StartupGate noOpStartupGate() {
        return new NoOpStartupGate();
    }

    /**
     * 模型路由：全部外部化到 application.yml 的 yunxi.models（catalog / routing / defaults），
     * 代码中不出现具体模型名；精排器不可用时回退规则式。
     */
    @Bean
    public ModelRoutingPolicy modelRoutingPolicy(
            ModelProperties modelProperties,
            ObjectProvider<FineRankingScorer> fineRankingScorer) {
        // 模型路由全部外部化到 application.yml 的 yunxi.models（catalog / routing / defaults），
        // 代码中不再出现具体模型名；未来新增模型或切换默认模型只需改配置。
        return new ConfigurableModelRoutingPolicy(
                modelProperties, fineRankingScorer.getIfAvailable(RuleBasedFineRankingScorer::new));
    }

    /**
     * 可选精排器：默认关闭。开启后由本地决策模型对自动选型候选做二次排序；
     * 决策模型不可用或打分失败时由 {@link Telev1FineRankingScorer} 自动回退规则式。
     */
    @Bean
    @ConditionalOnProperty(prefix = "yunxi.models.auto.fine-ranking", name = "enabled", havingValue = "true")
    public FineRankingScorer telev1FineRankingScorer(
            @Value("${yunxi.models.auto.fine-ranking.model-id:tev1:0.8b}") String modelId) {
        // 决策模型经框架 ModelRegistry 按 provider:model 解析；不可解析时由 Telev1FineRankingScorer 回退规则式。
        ModelResolver resolver = ModelRegistry::resolve;
        return new Telev1FineRankingScorer(resolver, modelId);
    }

    /** 未启用决策模型精排时的默认精排器（恒等，沿用规则式结果）。 */
    @Bean
    @ConditionalOnMissingBean(FineRankingScorer.class)
    public FineRankingScorer ruleBasedFineRankingScorer() {
        return new RuleBasedFineRankingScorer();
    }

    /**
     * 模型工厂：经框架 ModelRegistry 按 provider:modelName 解析（内置多 Provider 扩展），
     * yunxi 不维护第二份 Provider 实现，仅把模型 id 透传给框架注册表解析。
     */
    @Bean
    public ModelFactory modelFactory() {
        // 模型经框架 ModelRegistry 按 provider:modelName 模式解析（内置 DashScope / OpenAI / Anthropic / Gemini /
        // DeepSeek 等扩展，对应扩展在 classpath 上且环境变量提供 API Key 时自动建出真实 Model）。
        // yunxi 不维护第二份 Provider 实现，仅把模型 id 透传给框架注册表解析。
        return modelId -> ModelRegistry.resolve(modelId);
    }

    /** 默认内存版审计事件出口。 */
    @Bean
    public AuditSink auditSink() {
        return new InMemoryAuditSink();
    }

    /** 默认内存版度量注册表。 */
    @Bean
    public MetricRegistry metricRegistry() {
        return new InMemoryMetricRegistry();
    }

    /** 默认内存版轨迹存储（保留插入序，供按时间回放）。 */
    @Bean
    public TraceStore traceStore() {
        return new InMemoryTraceStore();
    }

    @Bean
    public List<MiddlewareBase> protocolAgnosticMiddlewares(
            AuditSink auditSink, MetricRegistry metricRegistry, ModelRoutingPolicy modelRoutingPolicy,
            SessionToolGroupRegistry sessionToolGroupRegistry, TenantQuotaMiddleware tenantQuotaMiddleware) {
        // 协议无关横切中间件在此统一装配（审计 / 追踪 / 人机协同 / 韧性 / 会话级工具组激活 / 租户配额）。
        // 所有协议（web / A2A / AG-UI / Chat Completions）统一经此链；运行时装配点按 order 挂载。
        // 跨会话长期记忆已下沉为 AgentScope-Java 框架原生的 MEMORY.md（由框架组件扫描注册），
        // 不再由 yunxi 自建中间件接兂，故此处只装配 yunxi 拥有的横切中间件。
        return new ArrayList<>(List.of(
                tenantQuotaMiddleware,
                new ChatAuditMiddleware(auditSink),
                new OtelTracingMiddleware(metricRegistry),
                new HitlMiddleware(auditSink, metricRegistry),
                new ResilienceMiddleware(metricRegistry, auditSink, modelRoutingPolicy),
                new SessionToolGroupActivationMiddleware(sessionToolGroupRegistry)));
    }

    /**
     * 集群模式运行时后端：{@code yunxi.cluster.mode=cluster} 时装配 Redis 分布式后端，
     * 启用跨实例 CAS 与会话轮次跨副本互斥。纯配置驱动，无需改程序源码。
     */
    @Bean
    @ConditionalOnProperty(name = "yunxi.cluster.mode", havingValue = "cluster")
    public DistributedRuntimeConfig clusterRuntimeConfig(
            StringRedisTemplate redisTemplate,
            @Value("${yunxi.cluster.key-prefix:yunxi:}") String keyPrefix) {
        return new RedisDistributedRuntimeConfig(redisTemplate, keyPrefix);
    }

    /**
     * 跨实例事件总线后端（cluster 模式）。复用全局 Lettuce 工厂与集群同一 key-prefix，和
     * RedisDistributedRuntimeConfig 同源共享命名空间，不私建 Redis 连接。
     * 仅 cluster 模式装配：standalone 无此 bean，AgentConfigurer / YunxiAgentBuilderSupplier
     * 经 ObjectProvider 安全跳过，agent 仍可布建（fail-open，主流程不阻断）。
     */
    @Bean
    @ConditionalOnProperty(name = "yunxi.cluster.mode", havingValue = "cluster")
    public RedisMessageBus clusterMessageBus(
            StringRedisTemplate redisTemplate,
            @Value("${yunxi.cluster.key-prefix:yunxi:}") String keyPrefix) {
        return new RedisMessageBus(redisTemplate, keyPrefix);
    }

    /**
     * 跨实例业务事件总线门面（cluster 模式）。封装 {@code clusterMessageBus}（Redis pub/sub），
     * 提供强类型事件发布 / 订阅与 JSON 路由（事件契约见 {@link CrossInstanceEvent}）。仅 cluster 模式
     * 装配：standalone 无此 bean，订阅方经 ObjectProvider 安全跳过（fail-open）。
     */
    @Bean
    @ConditionalOnProperty(name = "yunxi.cluster.mode", havingValue = "cluster")
    public CrossInstanceEventBus crossInstanceEventBus(RedisMessageBus clusterMessageBus) {
        return new CrossInstanceEventBus(clusterMessageBus);
    }

    /**
     * 单机（默认）运行时后端：未设 {@code yunxi.cluster.mode} 或显式 standalone 时装配内存兜底后端，
     * 保证无 Redis 的演示 / 开发环境零配置可启动。生产显式设 cluster 即切 Redis。
     */
    @Bean
    @ConditionalOnProperty(name = "yunxi.cluster.mode", havingValue = "standalone", matchIfMissing = true)
    public DistributedRuntimeConfig standaloneRuntimeConfig() {
        return new InMemoryDistributedRuntimeConfig();
    }

    /**
     * RAG 分块存储后端（框架共享存储抽象）。来自分布式运行时配置：分布式部署为框架 Redis 实现，
     * 单机为内存实现。其存在使 RAG 检索平面（SharedStoreBackedDocumentChunkStore）按需装配。
     */
    @Bean
    public BaseStore documentChunkStoreBackend(DistributedRuntimeConfig config) {
        return config.sharedChunkStore();
    }

    /** 默认 AgentSpec 结构校验器。 */
    @Bean
    public AgentSpecValidator agentSpecValidator() {
        return new DefaultAgentSpecValidator();
    }

    @Bean
    public AgentDefinitionLoader agentDefinitionLoader(ConfigCenter configCenter, AgentSpecValidator agentSpecValidator) {
        // 定义加载器：启动期扫描 classpath agent-definitions/*.yml，写入配置中心（全平台 Agent 定义唯一真相源）。
        // AgentDefinitionLoader 未标注 @Component，故在此显式声明以确保 @PostConstruct 触发
        // （io.yunxi.runtime 现已纳入应用组件扫描，但本类仍需显式装配）。
        return new AgentDefinitionLoader(configCenter, agentSpecValidator);
    }

    /** 会话级工具组注册表（供工具组激活中间件读取）。 */
    @Bean
    public SessionToolGroupRegistry sessionToolGroupRegistry() {
        return new SessionToolGroupRegistry();
    }

    /** 权限模板解析器（工具调用鉴权模板渲染）。 */
    @Bean
    public PermissionTemplateResolver permissionTemplateResolver() {
        return new PermissionTemplateResolver();
    }

    /** 多租户轴配置 Bean：从 {@link TenantProperties} 构建，默认关闭（装饰器 pass-through）。 */
    @Bean
    public TenantConfig tenantConfig(TenantProperties tenantProperties) {
        return new TenantConfig(tenantProperties.isEnabled(), tenantProperties.getDefaultTenant());
    }

    /** 运行时唯一装配点：收集全部依赖构建 AgentConfigurer（构建 HarnessAgent 的收口）。 */
    @Bean
    public AgentConfigurer agentConfigurer(
            ConfigCenter configCenter,
            TenantConfig tenantConfig,
            McpDirectory mcpDirectory,
            ModelRoutingPolicy modelRoutingPolicy,
            ModelFactory modelFactory,
            DistributedRuntimeConfig distributedRuntimeConfig,
            AgentSpecValidator agentSpecValidator,
            List<MiddlewareBase> protocolAgnosticMiddlewares,
            List<MuseToolRegistrar> museToolRegistrars,
            List<RagToolRegistrar> ragToolRegistrars,
            ApplicationEventPublisher applicationEventPublisher,
            List<AgentBuildContributor> agentBuildContributors,
            SessionToolGroupRegistry sessionToolGroupRegistry,
            PermissionTemplateResolver permissionTemplateResolver,
            MemoryProperties memoryProperties,
            @Value("${yunxi.muse.builtin-export-dir:.agentscope/workspace/skills}") String builtinExportDir) {
        return new AgentConfigurer(
                configCenter,
                mcpDirectory,
                modelRoutingPolicy,
                modelFactory,
                distributedRuntimeConfig,
                agentSpecValidator,
                protocolAgnosticMiddlewares,
                museToolRegistrars,
                ragToolRegistrars,
                applicationEventPublisher,
                agentBuildContributors,
                sessionToolGroupRegistry,
                permissionTemplateResolver,
                memoryProperties,
                builtinExportDir,
                tenantConfig);
    }

    @Bean
    public AguiAgentRegistry aguiAgentRegistry() {
        // 框架协议层 in-process 单例 Agent 真相源（AgentScope-Java AguiAgentRegistry，源自 agentscope-extensions-agui）。
        // 所有 Agent 注册于此；yunxi 自有 ingress 经 AgentLookup 读取，消除第二份注册表。
        return new AguiAgentRegistry();
    }

    /** 把 AgentLookup 收口到框架协议层 AguiAgentRegistry（进程内 Agent 唯一真相源）。 */
    @Bean
    public AgentLookup agentLookup(AguiAgentRegistry aguiAgentRegistry) {
        return new FrameworkAgentLookup(aguiAgentRegistry);
    }

    /** 四协议统一的身份归一解析器（运行时平面，单一真相源；ProtocolUserIdResolver 未标注 @Component，故在此显式声明）。 */
    @Bean
    public ProtocolUserIdResolver protocolUserIdResolver() {
        return new ProtocolUserIdResolver();
    }

    /**
     * 统一网关核心（AgentScope-Java HarnessGateway）：web / Chat Completions 执行门面经其 runStream 驱动 Agent。
     * 身份与富化经 {@code ChannelRuntimeContextResolver}（yunxi 实现 YunxiChannelRuntimeContextResolver）收口，
     * 会话轮次闸门经 {@code setSessionTurnGate} 注入（与 AG-UI 适配器共用同一分布式后端闸门，保证跨协议互斥）；
     * Agent 真相源由 AgentProvisioner 在布建期经 {@code registerAgent} 统一注册进本网关 agentRegistry。
     */
    @Bean
    public HarnessGateway harnessGateway(ObjectProvider<YunxiChannelRuntimeContextResolver> resolver,
                                         DistributedRuntimeConfig distributedRuntimeConfig) {
        // 解析器在完整应用上下文由 @Component 扫描提供；独立装配图（如 ReactorWiringTest 仅注册本配置类、
        // 无组件扫描）场景下回退到直接构造，保证 Bean 可创建、runStream 路径可装配。
        HarnessGateway gw = HarnessGateway.create();
        gw.setRuntimeContextResolver(resolver.getIfAvailable(YunxiChannelRuntimeContextResolver::new));
        gw.setSessionTurnGate(distributedRuntimeConfig.sessionTurnGate());
        return gw;
    }

    /** 租户 → Agent 分配授权服务：四协议真实闸门注入本服务做越权拦截（与各入口自身绑定的 TenantContext 配合）。 */
    @Bean
    public TenantAgentAllocationService tenantAgentAllocationService(ConfigCenter configCenter) {
        return new TenantAgentAllocationService(configCenter);
    }

    /**
     * 执行门面：web / Chat Completions 统一经 AgentScope-Java HarnessGateway#runStream 驱动 Agent。
     * Agent 查找（agentRegistry）、身份富化（ChannelRuntimeContextResolver）、会话轮次闸门（SessionTurnGate）
     * 全部由网关承担，本门面仅做「Command → MsgContext + Msg」薄翻译。租户授权在 findAgent 之前经
     * TenantAgentAllocationService 拦截。
     */
    @Bean
    public ExecutionFacade executionFacade(HarnessGateway harnessGateway,
            TenantAgentAllocationService allocationService) {
        return new DefaultExecutionFacade(harnessGateway, allocationService);
    }

    /**
     * 仅保留 web 协议适配器：单 Agent 的 web 入口由 yunxi 自有；
     * A2A / AG-UI / Chat Completions 三个协议改由框架 web starter 直接服务，不在自有 ingress 内重建。
     */
    @Bean
    public List<ProtocolAdapter> protocolAdapters() {
        // 仅保留 web 协议适配器：单 Agent 的 web 入口（/api/conversations）由 yunxi 自有；
        // A2A / AG-UI / Chat Completions 三个协议改由 AgentScope-Java 框架 web starter 直接服务，
        // 不在自有 ingress 内重建。
        return List.of(new WebProtocolAdapter());
    }

    /** 四协议聚合入口网关（聚合 web 执行门面与意图引擎）。 */
    @Bean
    public IngressGateway ingressGateway(ExecutionFacade executionFacade,
            List<ProtocolAdapter> protocolAdapters, IntentEngine intentEngine,
            TenantLifecycleService tenantLifecycleService) {
        return new IngressGateway(executionFacade, protocolAdapters, intentEngine, tenantLifecycleService);
    }

    /** 多租户解析器 Bean：按配置头名从入站 HTTP 头提取租户（默认 {@code X-Tenant-Id}）。 */
    @Bean
    public TenantResolver tenantResolver(TenantProperties tenantProperties) {
        return new HeaderTenantResolver(tenantProperties.getHeaderName());
    }

    /** 租户生命周期编排（四协议准入闸门 {@code admit} 的单一实现）。 */
    @Bean
    public TenantLifecycleService tenantLifecycleService(ConfigCenter configCenter) {
        return new TenantLifecycleService(configCenter);
    }

    /**
     * 租户配额中间件（协议无关横切闸门）：按租户维度强制执行每日请求数与并发会话数配额。
     * 复用平台全局 {@code StringRedisTemplate}（经 {@code ObjectProvider} 取用，无 Redis 时 fail-open）；
     * 挂入 {@code protocolAgnosticMiddlewares} 单一接线点，四协议统一生效。
     */
    @Bean
    public TenantQuotaMiddleware tenantQuotaMiddleware(TenantQuotaProperties properties,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        return new TenantQuotaMiddleware(properties, redisTemplateProvider);
    }

    /** 四协议聚合 HTTP 入口控制器（对外唯一端点，横切关切已由运行时装配点统一承担）。 */
    @Bean
    public ConversationController conversationController(IngressGateway ingressGateway,
            ProtocolUserIdResolver protocolUserIdResolver,
            TenantResolver tenantResolver,
            TenantConfig tenantConfig,
            ObjectProvider<ShardForwarder> shardForwarder) {
        // 四协议聚合的 HTTP 入口；经此唯一端点对外暴露运行时，所有横切关切已由运行时装配点统一承担。
        // shardForwarder 以 ObjectProvider 注入：分片关闭时无 ShardForwarder bean → 空 → fail-open 本地兜底。
        return new ConversationController(ingressGateway, protocolUserIdResolver, tenantResolver,
                tenantConfig, shardForwarder);
    }

    /**
     * 分片路由总开关：{@code yunxi.shard.enabled}（默认关闭）。开启后本实例仅布建命中一致性哈希环分片的 agent，
     * 关闭时全量布建（fail-open，行为等同现状）。
     */

    /**
     * Nacos 命名服务 Bean：仅当分片开启时创建，供 {@link NacosBackedShardResolver} 与
     * {@link io.yunxi.app.shard.ShardInstanceRegistrar} 复用（沿用 {@code nacos-client:3.2.4}，不引 spring-cloud-alibaba）。
     * 连接参数复用 {@link ConfigCenterNacosProperties} 的 serverAddr / namespace（同一 Nacos 集群）。
     */
    @Bean
    @ConditionalOnProperty(name = "yunxi.shard.enabled", havingValue = "true")
    public NamingService shardNamingService(ConfigCenterNacosProperties props) throws NacosException {
        Properties properties = new Properties();
        properties.setProperty("serverAddr", props.getServerAddr());
        if (props.getNamespace() != null && !props.getNamespace().isBlank()) {
            properties.setProperty("namespace", props.getNamespace());
        }
        return NacosFactory.createNamingService(properties);
    }

    /**
     * 分片关闭时的默认 {@link AgentShardResolver}：本实例拥有全部 agent（{@code owns} 永真），
     * 保证 {@code AgentProvisioner} 始终注入一个解析器；分片开启时由 {@link NacosBackedShardResolver} 取代（互斥）。
     */
    @Bean
    @ConditionalOnProperty(name = "yunxi.shard.enabled", havingValue = "false", matchIfMissing = true)
    public AgentShardResolver allOwnsShardResolver() {
        return new AllOwnsShardResolver();
    }

    /**
     * 跨实例转发客户端 Bean：仅当分片开启时创建，供 {@link io.yunxi.app.shard.AgentForwardingRouter} 复用。
     * 超时 / 重试当前取默认，后续可在配置中开放。
     */
    @Bean
    @ConditionalOnProperty(name = "yunxi.shard.enabled", havingValue = "true")
    public RestTemplate forwardingRestTemplate(
            @Value("${yunxi.shard.forward.connect-timeout-ms:3000}") int connectTimeout,
            @Value("${yunxi.shard.forward.read-timeout-ms:10000}") int readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return new RestTemplate(factory);
    }

    /**
     * AG-UI 跨实例流式转发过滤器：在 HTTP 边界拦截 AG-UI run 端点，non-owned {@code agentId} 经
     * {@link io.yunxi.app.shard.AgentForwardingRouter} 流式转发至 owner（框架 {@code AguiRestController} 仍驱动本地处理，
     * 本过滤器零改框架 bean、零子类化——框架 AG-UI 控制器构造不可继承，无法以子类覆盖方式替换）。
     * 分片关闭时 {@link io.yunxi.app.shard.AgentForwardingRouter} bean 缺失，过滤器退化为纯透传（fail-open 本地）。
     */
    @Bean
    public FilterRegistrationBean<AguiForwardingFilter> aguiForwardingFilter(AguiProperties aguiProperties,
            ObjectProvider<AgentForwardingRouter> agentForwardingRouterProvider) {
        FilterRegistrationBean<AguiForwardingFilter> registration = new FilterRegistrationBean<>(
                new AguiForwardingFilter(agentForwardingRouterProvider, aguiProperties));
        String prefix = aguiProperties.getPathPrefix();
        registration.addUrlPatterns(prefix + "/run", prefix + "/run/*");
        registration.setName("aguiForwardingFilter");
        return registration;
    }

}
