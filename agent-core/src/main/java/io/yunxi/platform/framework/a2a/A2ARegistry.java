package io.yunxi.platform.framework.a2a;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import io.yunxi.platform.infra.config.AgentscopeExtensionProperties.A2AConfig;
import lombok.extern.slf4j.Slf4j;

/**
 * A2A 注册中心抽象
 * 
 * <p>
 * 提供服务注册与发现能力，支持多种注册中心：
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
 * @version 1.0.0
 */
@Slf4j
@Service
// @ConditionalOnProperty(name = "agentscope.extensions.a2a.enabled",
// havingValue = "true")
public class A2ARegistry {

    /** A2A 配置 */
    private final A2AConfig config;

    /** 本地注册表（静态模式或缓存） */
    private final Map<String, List<A2AClient.AgentEndpoint>> localRegistry = new ConcurrentHashMap<>();

    /** Nacos 命名服务（如果使用 Nacos） */
    private Object nacosNamingService; // 使用 Object 避免强依赖

    /** Consul 客户端（如果使用 Consul） */
    private Object consulClient; // 使用 Object 避免强依赖

    /**
     * 构造 A2A 注册中心
     *
     * @param config A2A 配置（可选）
     */
    public A2ARegistry(ObjectProvider<A2AConfig> configProvider) {
        this.config = configProvider.getIfAvailable();
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
            log.info("A2AConfig 为空，使用默认静态注册中心");
            initStaticRegistry();
            return;
        }

        String registryType = config.getRegistryType();
        log.info("初始化 A2A 注册中心，类型: {}", registryType);

        switch (registryType.toLowerCase()) {
            case "nacos" -> initNacosRegistry();
            case "consul" -> initConsulRegistry();
            case "static" -> initStaticRegistry();
            default -> log.warn("未知的注册中心类型: {}", registryType);
        }
    }

    /**
     * 初始化 Nacos 注册中心
     */
    private void initNacosRegistry() {
        try {
            // 动态加载 Nacos 客户端（避免强制依赖）
            Class<?> nacosFactoryClass = Class.forName("com.alibaba.nacos.api.NacosFactory");
            Class<?> propertiesClass = Properties.class;

            Properties properties = new Properties();
            properties.setProperty("serverAddr", config.getRegistryAddr());
            if (config.getNamespace() != null) {
                properties.setProperty("namespace", config.getNamespace());
            }

            // 创建 NamingService
            var createNamingServiceMethod = nacosFactoryClass.getMethod("createNamingService", propertiesClass);
            this.nacosNamingService = createNamingServiceMethod.invoke(null, properties);

            log.info("Nacos 注册中心初始化成功: {}", config.getRegistryAddr());

        } catch (ClassNotFoundException e) {
            log.warn("Nacos 客户端未引入，请添加依赖: com.alibaba.nacos:nacos-client");
            log.info("回退到静态注册模式");
        } catch (Exception e) {
            log.error("Nacos 注册中心初始化失败: {}", e.getMessage());
        }
    }

    /**
     * 初始化 Consul 注册中心
     */
    private void initConsulRegistry() {
        try {
            // 动态加载 Consul 客户端（避免强制依赖）
            Class<?> consulClientClass = Class.forName("com.ecwid.consul.v1.ConsulClient");

            // 创建 Consul 客户端
            var constructor = consulClientClass.getConstructor(String.class, int.class);
            String[] addrParts = config.getRegistryAddr().split(":");
            String host = addrParts[0];
            int port = addrParts.length > 1 ? Integer.parseInt(addrParts[1]) : 8500;

            this.consulClient = constructor.newInstance(host, port);

            log.info("Consul 注册中心初始化成功: {}:{}", host, port);

        } catch (ClassNotFoundException e) {
            log.warn("Consul 客户端未引入，请添加依赖: com.ecwid.consul:consul-api");
            log.info("回退到静态注册模式");
        } catch (Exception e) {
            log.error("Consul 注册中心初始化失败: {}", e.getMessage());
        }
    }

    /**
     * 初始化静态注册（从配置文件读取）
     */
    private void initStaticRegistry() {
        log.info("使用静态注册模式");
        // 静态配置通过 register 方法手动注册
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
            // 使用反射调用 registerInstance
            var registerMethod = nacosNamingService.getClass().getMethod(
                    "registerInstance", String.class, String.class, int.class, Map.class);

            Map<String, String> metadata = new HashMap<>(registration.metadata());
            metadata.put("protocol", registration.protocol());

            registerMethod.invoke(
                    nacosNamingService,
                    registration.name(),
                    registration.host(),
                    registration.port(),
                    metadata);

            log.info("Agent 已注册到 Nacos: {}", registration.name());

        } catch (Exception e) {
            log.error("注册到 Nacos 失败: {}", registration.name(), e);
            // 回退到本地注册
            registerToLocal(registration);
        }
    }

    /**
     * 注册到 Consul
     */
    private void registerToConsul(AgentRegistration registration) {
        log.warn("Consul 注册待实现，回退到本地注册");
        registerToLocal(registration);
    }

    /**
     * 注册到本地（静态模式或缓存）
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

        log.info("Agent 已注册到本地: {} -> {}", registration.name(), endpoint.getUrl());
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
            // 查找本地注册表中的端点信息
            List<A2AClient.AgentEndpoint> endpoints = localRegistry.get(agentName);
            if (endpoints == null || endpoints.isEmpty()) {
                log.warn("Agent {} 未在本地注册表中找到", agentName);
                deregisterFromLocal(agentName);
                return;
            }

            // 使用反射调用 deregisterInstance
            // Nacos API: deregisterInstance(String serviceName, String ip, int port)
            var deregisterMethod = nacosNamingService.getClass().getMethod(
                    "deregisterInstance", String.class, String.class, int.class);

            for (A2AClient.AgentEndpoint endpoint : endpoints) {
                deregisterMethod.invoke(
                        nacosNamingService,
                        agentName,
                        endpoint.host(),
                        endpoint.port());
                log.info("已从 Nacos 注销: {} -> {}:{}", agentName, endpoint.host(), endpoint.port());
            }

        } catch (Exception e) {
            log.error("从 Nacos 注销失败: {}", agentName, e);
            // 确保本地注册表也被清理
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
            // 使用反射调用 Consul API
            // Consul API: agentServiceDeregister(String serviceId)
            var deregisterMethod = consulClient.getClass().getMethod("agentServiceDeregister", String.class);

            // 使用 agentName 作为 serviceId（或使用自定义规则）
            deregisterMethod.invoke(consulClient, agentName);

            log.info("已从 Consul 注销: {}", agentName);

        } catch (Exception e) {
            log.error("从 Consul 注销失败: {}", agentName, e);
        } finally {
            // 确保本地注册表也被清理
            deregisterFromLocal(agentName);
        }
    }

    /**
     * 从本地注销
     */
    private void deregisterFromLocal(String agentName) {
        localRegistry.remove(agentName);
        log.info("Agent 已从本地注销: {}", agentName);
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
            // 使用反射调用 selectInstances
            var selectMethod = nacosNamingService.getClass().getMethod(
                    "selectInstances", String.class, boolean.class);

            @SuppressWarnings("unchecked")
            List<?> instances = (List<?>) selectMethod.invoke(nacosNamingService, agentName, true);

            List<A2AClient.AgentEndpoint> endpoints = new ArrayList<>();
            for (Object instance : instances) {
                // 解析 Nacos Instance 对象
                var hostMethod = instance.getClass().getMethod("getIp");
                var portMethod = instance.getClass().getMethod("getPort");
                var metaMethod = instance.getClass().getMethod("getMetadata");

                String host = (String) hostMethod.invoke(instance);
                int port = (int) portMethod.invoke(instance);
                @SuppressWarnings("unchecked")
                Map<String, String> metadata = (Map<String, String>) metaMethod.invoke(instance);

                String protocol = metadata.getOrDefault("protocol", "http");

                endpoints.add(new A2AClient.AgentEndpoint(agentName, host, port, protocol, metadata));
            }

            // 更新本地缓存
            if (!endpoints.isEmpty()) {
                localRegistry.put(agentName, endpoints);
            }

            return endpoints;

        } catch (Exception e) {
            log.warn("从 Nacos 发现 Agent 失败: {}, 回退到本地缓存", agentName);
            return discoverFromLocal(agentName);
        }
    }

    /**
     * 从 Consul 发现
     */
    private List<A2AClient.AgentEndpoint> discoverFromConsul(String agentName) {
        log.warn("Consul 发现待实现，使用本地缓存");
        return discoverFromLocal(agentName);
    }

    /**
     * 从本地发现
     */
    private List<A2AClient.AgentEndpoint> discoverFromLocal(String agentName) {
        return localRegistry.getOrDefault(agentName, List.of());
    }

    /**
     * 获取所有已注册的 Agent
     *
     * @return Agent 名称列表
     */
    public List<String> getAllAgents() {
        return new ArrayList<>(localRegistry.keySet());
    }

    /**
     * 检查 Agent 是否已注册
     *
     * @param agentName Agent 名称
     * @return 是否已注册
     */
    public boolean isRegistered(String agentName) {
        List<A2AClient.AgentEndpoint> endpoints = discover(agentName);
        return !endpoints.isEmpty();
    }
}
