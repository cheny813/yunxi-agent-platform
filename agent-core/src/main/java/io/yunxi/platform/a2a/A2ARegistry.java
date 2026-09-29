package io.yunxi.platform.a2a;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import io.yunxi.platform.config.A2AConfig;
import io.yunxi.platform.infrastructure.nacos.NacosClientFactory;
import io.yunxi.platform.infrastructure.nacos.NacosConnection;
import lombok.extern.slf4j.Slf4j;

/**
 * A2A 注册中心抽象
 *
 * <p>
 * 提供服务注册与发现能力，支持多种注册中心实现：
 * </p>
 * <ul>
 * <li>Nacos - 阿里云服务发现</li>
 * <li>Consul - HashiCorp 服务发现</li>
 * <li>Static - 静态配置</li>
 * </ul>
 *
 * <h3>扩展自定义注册中心</h3>
 *
 * <pre>
 * &#64;Service
 * public class CustomA2ARegistry implements A2ARegistry {
 *     &#64;Override
 *     public void register(AgentRegistration registration) {
 *         // 实现注册逻辑
 *     }
 *
 *     &#64;Override
 *     public List&lt;AgentEndpoint&gt; discover(String agentName) {
 *         // 实现发现逻辑
 *     }
 * }
 * </pre>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Service
// @ConditionalOnProperty(name = "yunxi.a2a.enabled",
// havingValue = "true")
public class A2ARegistry {

    /** A2A 配置 */
    private final A2AConfig config;

    /** 统一 Nacos 客户端工厂（可选；缺失时 Nacos 注册降级为静态）。 */
    private final ObjectProvider<NacosClientFactory> nacosFactoryProvider;

    /** 本地注册表（静态模式或缓存） */
    private final Map<String, List<A2AClient.AgentEndpoint>> localRegistry = new ConcurrentHashMap<>();

    /** Nacos 命名服务（如使用 Nacos；nacos-client 已是 agent-core 硬依赖，故此处为强类型）。 */
    private NamingService nacosNamingService;

    /** Consul 客户端（如使用 Consul） */
    private Object consulClient; // 使用 Object 避免强依赖

    /**
     * 构造函数
     *
     * @param configProvider A2A 配置（可选）
     */
    public A2ARegistry(ObjectProvider<A2AConfig> configProvider,
                        ObjectProvider<NacosClientFactory> nacosFactoryProvider) {
        this.config = configProvider.getIfAvailable();
        this.nacosFactoryProvider = nacosFactoryProvider;
        initRegistry();
    }

    /**
     * Agent 注册信息
     */
    public record AgentRegistration(
            String name,
            String host,
            int port,
            String protocol,
            Map<String, String> metadata) {
    }

    /**
     * 初始化注册中心
     */
    private void initRegistry() {
        if (config == null) {
            log.info("A2AConfig 为空，使用静态注册");
            initStaticRegistry();
            return;
        }

        String registryType = config.getRegistryType();
        log.info("初始化 A2A 注册，类型: {}", registryType);

        switch (registryType.toLowerCase()) {
            case "nacos" -> initNacosRegistry();
            case "consul" -> initConsulRegistry();
            case "static" -> initStaticRegistry();
            default -> log.warn("未知注册类型: {}", registryType);
        }
    }

    /**
     * 初始化 Nacos 注册中心
     */
    private void initNacosRegistry() {
        NacosClientFactory factory = nacosFactoryProvider.getIfAvailable();
        if (factory == null) {
            log.warn("Nacos 客户端工厂不可用，降级静态注册");
            initStaticRegistry();
            return;
        }
        try {
            this.nacosNamingService = factory.createNamingService(
                    new NacosConnection(config.getRegistryAddr(), config.getNamespace(), "", ""));
            log.info("Nacos 注册初始化成功: {}", config.getRegistryAddr());
        } catch (Exception e) {
            log.error("Nacos 注册初始化失败: {}", e.getMessage());
            this.nacosNamingService = null;
            initStaticRegistry();
        }
    }

    /**
     * 初始化 Consul 注册
     */
    private void initConsulRegistry() {
        try {
            // 动态加载 Consul 客户端（可选）
            Class<?> consulClientClass = Class.forName("com.ecwid.consul.v1.ConsulClient");

            // 创建 Consul 客户端
            var constructor = consulClientClass.getConstructor(String.class, int.class);
            String[] addrParts = config.getRegistryAddr().split(":");
            String host = addrParts[0];
            int port = addrParts.length > 1 ? Integer.parseInt(addrParts[1]) : 8500;

            this.consulClient = constructor.newInstance(host, port);

            log.info("Consul 注册初始化成功: {}:{}", host, port);

        } catch (ClassNotFoundException e) {
            log.warn("Consul 依赖未找到，请添加依赖: com.ecwid.consul:consul-api");
            log.info("使用静态注册模式");
        } catch (Exception e) {
            log.error("Consul 注册初始化失败: {}", e.getMessage());
        }
    }

    /**
     * 静态注册（本地）
     */
    private void initStaticRegistry() {
        log.info("使用静态注册模式");
        // 通过 register 方法注册
    }

    // ==================== 服务注册 ====================

    /**
     * 注册 Agent
     *
     * @param registration Agent 注册信息
     */
    public void register(AgentRegistration registration) {
        String registryType = config.getRegistryType();
        log.info("注册 Agent: {} -> {}:{}", registration.name(), registration.host(), registration.port());

        try {
            switch (registryType.toLowerCase()) {
                case "nacos" -> registerToNacos(registration);
                case "consul" -> registerToConsul(registration);
                default -> registerToLocal(registration);
            }
        } catch (Exception e) {
            log.error("Agent 注册失败: {}", registration.name(), e);
        }
    }

    /**
     * 注册到 Nacos
     */
    private void registerToNacos(AgentRegistration registration) {
        if (nacosNamingService == null) {
            registerToLocal(registration);
            return;
        }

        try {
            Instance instance = new Instance();
            instance.setServiceName(registration.name());
            instance.setIp(registration.host());
            instance.setPort(registration.port());
            Map<String, String> metadata = new HashMap<>(registration.metadata());
            metadata.put("protocol", registration.protocol());
            instance.setMetadata(metadata);
            nacosNamingService.registerInstance(instance.getServiceName(), instance);
            log.info("Agent 注册到 Nacos: {}", registration.name());
        } catch (Exception e) {
            log.error("注册到 Nacos 失败: {}", registration.name(), e);
            registerToLocal(registration);
        }
    }

    /**
     * 注册到 Consul
     */
    private void registerToConsul(AgentRegistration registration) {
        log.warn("Consul 注册未实现，降级注册");
        registerToLocal(registration);
    }

    /**
     * 本地注册（静态模式缓存）
     */
    private void registerToLocal(AgentRegistration registration) {
        A2AClient.AgentEndpoint endpoint = new A2AClient.AgentEndpoint(
                registration.name(),
                registration.host(),
                registration.port(),
                registration.protocol(),
                registration.metadata());

        localRegistry.computeIfAbsent(registration.name(), k -> new ArrayList<>())
                .add(endpoint);

        log.info("Agent 注册到本地: {} -> {}", registration.name(), endpoint.getUrl());
    }

    /**
     * 注销 Agent
     *
     * @param agentName Agent 名称
     */
    public void deregister(String agentName) {
        log.info("注销 Agent: {}", agentName);

        String registryType = config.getRegistryType();

        try {
            switch (registryType.toLowerCase()) {
                case "nacos" -> deregisterFromNacos(agentName);
                case "consul" -> deregisterFromConsul(agentName);
                default -> deregisterFromLocal(agentName);
            }
        } catch (Exception e) {
            log.error("Agent 注销失败: {}", agentName, e);
        }
    }

    /**
     * 从 Nacos 注销
     */
    private void deregisterFromNacos(String agentName) {
        if (nacosNamingService == null) {
            deregisterFromLocal(agentName);
            return;
        }

        try {
            List<A2AClient.AgentEndpoint> endpoints = localRegistry.get(agentName);
            if (endpoints == null || endpoints.isEmpty()) {
                log.warn("Agent {} 未注册", agentName);
                deregisterFromLocal(agentName);
                return;
            }

            for (A2AClient.AgentEndpoint endpoint : endpoints) {
                nacosNamingService.deregisterInstance(agentName, endpoint.host(), endpoint.port());
                log.info("从 Nacos 注销: {} -> {}:{}", agentName, endpoint.host(), endpoint.port());
            }
        } catch (Exception e) {
            log.error("从 Nacos 注销失败: {}", agentName, e);
            deregisterFromLocal(agentName);
        }
    }

    /**
     * 从 Consul 注销
     */
    private void deregisterFromConsul(String agentName) {
        if (consulClient == null) {
            deregisterFromLocal(agentName);
            return;
        }

        try {
            // 使用 Consul API
            // Consul API: agentServiceDeregister(String serviceId) 注销服务
            var deregisterMethod = consulClient.getClass().getMethod("agentServiceDeregister", String.class);

            // 使用 agentName 作为 serviceId（可调整）
            deregisterMethod.invoke(consulClient, agentName);

            log.info("从 Consul 注销: {}", agentName);

        } catch (Exception e) {
            log.error("从 Consul 注销失败: {}", agentName, e);
        } finally {
            // 确保本地也清理
            deregisterFromLocal(agentName);
        }
    }

    /**
     * 本地注销
     */
    private void deregisterFromLocal(String agentName) {
        localRegistry.remove(agentName);
        log.info("Agent 从本地注销: {}", agentName);
    }

    // ==================== 服务发现 ====================

    /**
     * 发现 Agent 端点
     *
     * @param agentName Agent 名称
     * @return 端点列表
     */
    public List<A2AClient.AgentEndpoint> discover(String agentName) {
        String registryType = config.getRegistryType();

        return switch (registryType.toLowerCase()) {
            case "nacos" -> discoverFromNacos(agentName);
            case "consul" -> discoverFromConsul(agentName);
            default -> discoverFromLocal(agentName);
        };
    }

    /**
     * 从 Nacos 发现
     */
    private List<A2AClient.AgentEndpoint> discoverFromNacos(String agentName) {
        if (nacosNamingService == null) {
            return discoverFromLocal(agentName);
        }

        try {
            List<Instance> instances = nacosNamingService.selectInstances(agentName, true);
            List<A2AClient.AgentEndpoint> endpoints = new ArrayList<>();
            for (Instance instance : instances) {
                String protocol = instance.getMetadata().getOrDefault("protocol", "http");
                endpoints.add(new A2AClient.AgentEndpoint(agentName, instance.getIp(), instance.getPort(),
                        protocol, instance.getMetadata()));
            }

            if (!endpoints.isEmpty()) {
                localRegistry.put(agentName, endpoints);
            }

            return endpoints;
        } catch (Exception e) {
            log.warn("从 Nacos 发现 Agent 失败: {}, 降级本地", agentName);
            return discoverFromLocal(agentName);
        }
    }

    /**
     * 从 Consul 发现
     */
    private List<A2AClient.AgentEndpoint> discoverFromConsul(String agentName) {
        log.warn("Consul 未实现，使用降级");
        return discoverFromLocal(agentName);
    }

    /**
     * 本地发现
     */
    private List<A2AClient.AgentEndpoint> discoverFromLocal(String agentName) {
        return localRegistry.getOrDefault(agentName, List.of());
    }

    /**
     * 获取已注册 Agent
     *
     * @return Agent 列表
     */
    public List<String> getAllAgents() {
        return new ArrayList<>(localRegistry.keySet());
    }

    /**
     * 检查 Agent 是否注册
     *
     * @param agentName Agent 名称
     * @return 是否注册
     */
    public boolean isRegistered(String agentName) {
        List<A2AClient.AgentEndpoint> endpoints = discover(agentName);
        return !endpoints.isEmpty();
    }
}
