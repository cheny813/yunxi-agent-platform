package io.yunxi.app.config;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import io.yunxi.config.ConfigCenter;
import io.yunxi.config.ConfigFragment;
import io.yunxi.config.InMemoryConfigCenter;
import io.yunxi.config.TenantAgentAllocation;
import io.yunxi.definition.AgentSpec;
import io.yunxi.definition.Tenant;
import io.yunxi.fabric.CrossInstanceEvent;
import io.yunxi.fabric.CrossInstanceEventBus;
import io.yunxi.fabric.CrossInstanceEventType;
import io.yunxi.runtime.Json;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 应用平面。基于 Nacos 的 {@link ConfigCenter} 实现：Agent 定义以全量快照形式持久化到
 * Nacos {@code dataId}，多实例共享同一权威目录（解决 classpath 种子在单机、多实例分叉的问题）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>纯本地解析 {@code resolve} 抽为接口默认方法（与内存实现共用），本类不重复实现；</li>
 *   <li>Nacos 不可达或关闭时优雅降级为内存版（{@code fallback}），不阻断启动（fail-open）；</li>
 *   <li>启动时从 Redis 共享读缓存 hydrate 内存镜像（未命中回源 Nacos 并回填），注册/注销时更新镜像、回写 Nacos 并同步回填 Redis（全量快照语义）；</li>
 *   <li>注册 Nacos 监听器，目录变更时实时热加载并回填 Redis；Redis 缺失时自动退化为纯 Nacos + 内存（fail-open）。</li>
 * </ul>
 *
 * <p>与「配置中心可切换后端」设计一致：opt-in 默认 InMemory，生产可按需接入共享存储。Redis 客户端复用
 * Spring Boot 自动配置的全局 {@code StringRedisTemplate}（全局 {@code LettuceConnectionFactory} 唯一），不新建连接。</p>
 */
public class NacosBackedConfigCenter implements ConfigCenter {

    private static final Logger log = LoggerFactory.getLogger(NacosBackedConfigCenter.class);

    private static final String REDIS_CACHE_PREFIX = "yunxi:config-center:";
    private static final String TENANTS_DATA_ID = "yunxi:tenants";

    private final ConfigCenterNacosProperties props;
    private final ConfigService configService; // null = 降级内存
    private final StringRedisTemplate redis; // null = Redis 不可用/未配置，读缓存退化为 Nacos+内存
    private final InMemoryConfigCenter fallback = new InMemoryConfigCenter();
    private volatile List<AgentSpec> image = new ArrayList<>();
    private volatile TenantAgentAllocation allocationImage = new TenantAgentAllocation(Map.of(), Set.of());
    private volatile List<Tenant> tenantImage = new ArrayList<>();

    /**
     * 跨实例业务事件总线（{@code yunxi.cluster.mode=cluster} 时由 RuntimeConfiguration 装配的
     * {@code CrossInstanceEventBus} bean；standalone 或总线 bean 缺失时为 null，本类所有发布 / 订阅
     * 均安全跳过，配置仍由 Nacos 订阅 + Redis 共享缓存兜底（fail-open）。
     */
    @Autowired
    private ObjectProvider<CrossInstanceEventBus> eventBusProvider;

    /** 生产构造：按配置连 Nacos（不可达/关闭则降级内存），并注入全局 Redis 共享读缓存（缺失则自动退化为 Nacos+内存）。 */
    public NacosBackedConfigCenter(ConfigCenterNacosProperties props, ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this(initConfigService(props), props, redisTemplateProvider == null ? null : redisTemplateProvider.getIfAvailable());
    }

    /** 测试构造：直接注入 ConfigService（可为 null 模拟降级），避免测试连接真实 Nacos；Redis 为 null（默认）。 */
    NacosBackedConfigCenter(ConfigService configService, ConfigCenterNacosProperties props) {
        this(configService, props, null);
    }

    /** 测试构造：直接注入 ConfigService 与 Redis 替身，供 Redis 读缓存行为测试。 */
    NacosBackedConfigCenter(ConfigService configService, ConfigCenterNacosProperties props, StringRedisTemplate redis) {
        this.props = props;
        this.configService = configService;
        this.redis = redis;
        if (configService != null) {
            refreshImage();
            registerListener();
            refreshAllocation();
            registerAllocationListener();
            refreshTenants();
            registerTenantsListener();
        }
    }

    private static ConfigService initConfigService(ConfigCenterNacosProperties p) {
        if (!p.isEnabled()) {
            log.warn("[ConfigCenter] Nacos 未启用（yunxi.config.nacos.enabled=false），退化为本地内存。");
            return null;
        }
        try {
            Properties properties = new Properties();
            properties.setProperty("serverAddr", p.getServerAddr());
            if (p.getNamespace() != null && !p.getNamespace().isBlank()) {
                properties.setProperty("namespace", p.getNamespace());
            }
            ConfigService cs = NacosFactory.createConfigService(properties);
            log.info("[ConfigCenter] 已接入 Nacos {}，dataId={} group={}",
                    p.getServerAddr(), p.getDataId(), p.getGroup());
            return cs;
        } catch (NacosException e) {
            log.error("[ConfigCenter] Nacos 不可达或初始化失败，退化为本地内存：{}", e.getMessage());
            return null;
        }
    }

    private String cacheKey(String dataId) {
        return REDIS_CACHE_PREFIX + dataId;
    }

    /** 读 Redis 共享读缓存；redis 为 null 或异常时返回 null（降级 Nacos）。 */
    private String readRedisCache(String dataId) {
        if (redis == null) {
            return null;
        }
        try {
            return redis.opsForValue().get(cacheKey(dataId));
        } catch (RuntimeException e) {
            log.warn("[ConfigCenter] 读 Redis 共享读缓存失败（降级 Nacos），dataId={}：{}", dataId, e.getMessage());
            return null;
        }
    }

    /** 写 Redis 共享读缓存；redis 为 null 或 content 为 null 或异常时跳过（缓存缺失不影响 Nacos 权威）。 */
    private void writeRedisCache(String dataId, String content) {
        if (redis == null || content == null) {
            return;
        }
        try {
            redis.opsForValue().set(cacheKey(dataId), content);
        } catch (RuntimeException e) {
            log.warn("[ConfigCenter] 写 Redis 共享读缓存失败（缓存缺失不影响 Nacos 权威），dataId={}：{}", dataId, e.getMessage());
        }
    }

    private synchronized void refreshImage() {
        String cached = readRedisCache(props.getDataId());
        if (cached != null) {
            image = parseAgentSpecs(cached);
            return;
        }
        String raw = loadRaw(props.getDataId());
        image = parseAgentSpecs(raw);
        if (raw != null) {
            writeRedisCache(props.getDataId(), raw);
        }
    }

    private synchronized void refreshAllocation() {
        String cached = readRedisCache(props.getAllocationDataId());
        if (cached != null) {
            allocationImage = parseAllocation(cached);
            return;
        }
        String raw = loadRaw(props.getAllocationDataId());
        allocationImage = parseAllocation(raw);
        if (raw != null) {
            writeRedisCache(props.getAllocationDataId(), raw);
        }
    }

    /** 从 Nacos 读取某 dataId 的原始内容；失败返回 null（保留上次镜像）。 */
    private String loadRaw(String dataId) {
        try {
            return configService.getConfig(dataId, props.getGroup(), 5000);
        } catch (NacosException e) {
            log.error("[ConfigCenter] 读取 Nacos 配置失败 dataId={}，保留上次镜像：{}", dataId, e.getMessage());
            return null;
        }
    }

    /** 注册某 dataId 的监听器；变化时回调 onUpdate（同步执行，复用调用线程）。 */
    private void addListener(String dataId, Runnable onUpdate) {
        try {
            configService.addListener(dataId, props.getGroup(), new Listener() {
                @Override
                public Executor getExecutor() {
                    return Runnable::run;
                }

                @Override
                public void receiveConfigInfo(String cfg) {
                    onUpdate.run();
                }
            });
        } catch (NacosException e) {
            log.error("[ConfigCenter] 注册 Nacos 监听器失败 dataId={}：{}", dataId, e.getMessage());
        }
    }

    private void registerListener() {
        addListener(props.getDataId(), () -> {
            String raw = loadRaw(props.getDataId());
            image = parseAgentSpecs(raw);
            if (raw != null) {
                writeRedisCache(props.getDataId(), raw);
            }
            log.info("[ConfigCenter] 已从 Nacos 热加载 Agent 定义快照，条目={}", image.size());
        });
    }

    private void registerAllocationListener() {
        addListener(props.getAllocationDataId(), () -> {
            String raw = loadRaw(props.getAllocationDataId());
            allocationImage = parseAllocation(raw);
            if (raw != null) {
                writeRedisCache(props.getAllocationDataId(), raw);
            }
            log.info("[ConfigCenter] 已从 Nacos 热加载租户→Agent 分配快照");
        });
    }

    private synchronized void refreshTenants() {
        String cached = readRedisCache(TENANTS_DATA_ID);
        if (cached != null) {
            tenantImage = parseTenants(cached);
            return;
        }
        String raw = loadRaw(TENANTS_DATA_ID);
        tenantImage = parseTenants(raw);
        if (raw != null) {
            writeRedisCache(TENANTS_DATA_ID, raw);
        }
    }

    private void registerTenantsListener() {
        addListener(TENANTS_DATA_ID, () -> {
            String raw = loadRaw(TENANTS_DATA_ID);
            tenantImage = parseTenants(raw);
            if (raw != null) {
                writeRedisCache(TENANTS_DATA_ID, raw);
            }
            log.info("[ConfigCenter] 已从 Nacos 热加载租户定义快照，条目={}", tenantImage.size());
        });
    }

    private List<Tenant> parseTenants(String content) {
        if (content == null || content.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return Json.objectMapper().readValue(content,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Tenant>>() {});
        } catch (Exception e) {
            log.error("[ConfigCenter] 解析 Nacos 租户定义快照失败：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private void publishRaw(String dataId, String content) {
        ConfigService cs = configService;
        if (cs == null) {
            return;
        }
        try {
            cs.publishConfig(dataId, props.getGroup(), content);
        } catch (Exception e) {
            log.error("[ConfigCenter] 发布配置到 Nacos 失败 dataId={}：{}", dataId, e.getMessage());
        }
    }

    private List<AgentSpec> parseAgentSpecs(String content) {
        if (content == null || content.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return Json.objectMapper().readValue(content,
                    new com.fasterxml.jackson.core.type.TypeReference<List<AgentSpec>>() {});
        } catch (Exception e) {
            log.error("[ConfigCenter] 解析 Nacos Agent 定义快照失败：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private TenantAgentAllocation parseAllocation(String content) {
        if (content == null || content.isBlank()) {
            return new TenantAgentAllocation(Map.of(), Set.of());
        }
        try {
            return Json.objectMapper().readValue(content, TenantAgentAllocation.class);
        } catch (Exception e) {
            log.error("[ConfigCenter] 解析 Nacos 租户→Agent 分配快照失败：{}", e.getMessage());
            return new TenantAgentAllocation(Map.of(), Set.of());
        }
    }

    @Override
    public void registerFragment(ConfigFragment fragment) {
        fallback.registerFragment(fragment);
    }

    @Override
    public ConfigFragment getFragment(String id) {
        return fallback.getFragment(id);
    }

    @Override
    public synchronized void registerAgentSpec(AgentSpec spec) {
        fallback.registerAgentSpec(spec);
        AgentSpec existing = findInImage(spec.identity().agentId());
        List<AgentSpec> next = new ArrayList<>(image);
        if (existing != null) {
            next.remove(existing);
        }
        next.add(spec);
        image = next;
        publishImage();
    }

    @Override
    public AgentSpec getAgentSpec(String agentId) {
        for (AgentSpec s : image) {
            if (Objects.equals(s.identity().agentId(), agentId)) {
                return s;
            }
        }
        return fallback.getAgentSpec(agentId);
    }

    @Override
    public Collection<AgentSpec> listAgentSpecs() {
        if (!image.isEmpty()) {
            return List.copyOf(image);
        }
        return fallback.listAgentSpecs();
    }

    @Override
    public synchronized void removeAgentSpec(String agentId) {
        fallback.removeAgentSpec(agentId);
        AgentSpec existing = findInImage(agentId);
        if (existing != null) {
            List<AgentSpec> next = new ArrayList<>(image);
            next.remove(existing);
            image = next;
            publishImage();
        }
    }

    @Override
    public boolean containsAgentSpec(String agentId) {
        for (AgentSpec s : image) {
            if (Objects.equals(s.identity().agentId(), agentId)) {
                return true;
            }
        }
        return fallback.containsAgentSpec(agentId);
    }

    private AgentSpec findInImage(String agentId) {
        for (AgentSpec s : image) {
            if (Objects.equals(s.identity().agentId(), agentId)) {
                return s;
            }
        }
        return null;
    }

    private synchronized void publishImage() {
        try {
            String json = Json.objectMapper().writeValueAsString(image);
            publishRaw(props.getDataId(), json);
            writeRedisCache(props.getDataId(), json);
            // 跨实例即时扇出：通知其它实例从共享 Redis 缓存就近重载（增强 Nacos 监听延迟）。
            broadcast(CrossInstanceEventType.AGENT_DEF_CHANGED);
        } catch (Exception e) {
            log.error("[ConfigCenter] 序列化 Agent 定义快照失败：{}", e.getMessage());
        }
    }

    private synchronized void publishAllocation() {
        try {
            String json = Json.objectMapper().writeValueAsString(allocationImage);
            publishRaw(props.getAllocationDataId(), json);
            writeRedisCache(props.getAllocationDataId(), json);
            broadcast(CrossInstanceEventType.TENANT_CONFIG_CHANGED);
        } catch (Exception e) {
            log.error("[ConfigCenter] 序列化租户→Agent 分配快照失败：{}", e.getMessage());
        }
    }

    private synchronized void publishTenants() {
        try {
            String json = Json.objectMapper().writeValueAsString(tenantImage);
            publishRaw(TENANTS_DATA_ID, json);
            writeRedisCache(TENANTS_DATA_ID, json);
            broadcast(CrossInstanceEventType.TENANT_CONFIG_CHANGED);
        } catch (Exception e) {
            log.error("[ConfigCenter] 序列化租户定义快照失败：{}", e.getMessage());
        }
    }

    /**
     * 跨实例事件广播（fail-open）。总线 bean 缺失或发布抛错均不向上传播，配置仍由 Nacos 订阅兜底。
     */
    private void broadcast(CrossInstanceEventType type) {
        CrossInstanceEventBus bus = eventBusProvider != null ? eventBusProvider.getIfAvailable() : null;
        if (bus == null) {
            return;
        }
        bus.publish(CrossInstanceEvent.of(type, Map.of(), null));
    }

    /**
     * 订阅跨实例配置变更事件。仅在集群模式（事件总线 bean 存在）下生效；接收到 peer 广播后，从共享
     * Redis 缓存就近重载对应配置（单一真相源仍为 Nacos，Redis 为只读穿透缓存），无需等待 Nacos 监听回调。
     * 重加载幂等，重复触发无副作用（且不会再次广播，避免环回风暴）。
     */
    @PostConstruct
    public void initEventSubscriptions() {
        CrossInstanceEventBus bus = eventBusProvider != null ? eventBusProvider.getIfAvailable() : null;
        if (bus == null) {
            return;
        }
        bus.subscribe(CrossInstanceEventType.AGENT_DEF_CHANGED, event -> reloadFromSharedCache(event));
        bus.subscribe(CrossInstanceEventType.TENANT_CONFIG_CHANGED, event -> reloadFromSharedCache(event));
    }

    private void reloadFromSharedCache(CrossInstanceEvent event) {
        try {
            if (event.getType() == CrossInstanceEventType.AGENT_DEF_CHANGED) {
                refreshImage();
            } else if (event.getType() == CrossInstanceEventType.TENANT_CONFIG_CHANGED) {
                refreshAllocation();
                refreshTenants();
            }
        } catch (Exception e) {
            log.warn("[ConfigCenter] reload from shared cache on {} failed, fall back to Nacos: {}",
                    event.getType(), e.getMessage());
        }
    }

    @Override
    public void registerTenantAllocation(TenantAgentAllocation allocation) {
        fallback.registerTenantAllocation(allocation);
        allocationImage = allocation;
        publishAllocation();
    }

    @Override
    public TenantAgentAllocation getTenantAllocation() {
        return allocationImage;
    }

    @Override
    public synchronized void registerTenant(Tenant tenant) {
        fallback.registerTenant(tenant);
        Tenant existing = findInTenantImage(tenant.tenantId());
        List<Tenant> next = new ArrayList<>(tenantImage);
        if (existing != null) {
            next.remove(existing);
        }
        next.add(tenant);
        tenantImage = next;
        publishTenants();
    }

    @Override
    public Optional<Tenant> getTenant(String tenantId) {
        for (Tenant t : tenantImage) {
            if (Objects.equals(t.tenantId(), tenantId)) {
                return Optional.of(t);
            }
        }
        return fallback.getTenant(tenantId);
    }

    @Override
    public List<Tenant> listTenants() {
        if (!tenantImage.isEmpty()) {
            return List.copyOf(tenantImage);
        }
        return fallback.listTenants();
    }

    private Tenant findInTenantImage(String tenantId) {
        for (Tenant t : tenantImage) {
            if (Objects.equals(t.tenantId(), tenantId)) {
                return t;
            }
        }
        return null;
    }
}
