package io.yunxi.platform.agent.mcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.config.listener.AbstractListener;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.config.McpNacosProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * MCP 动态注册协调底座的 Nacos 客户端封装。
 * <p>承担「协调底座」职责，与运行期 MCP 连接（mcp-java-sdk）职责分离：</p>
 * <ul>
 *   <li>ConfigService：以 dataId/group 存储 mcp-servers 目录 JSON（增删改查）</li>
 *   <li>NamingService：注册协调实例，供跨 JVM 广播感知</li>
 *   <li>监听：dataId 变化回调，驱动本 JVM reconcile（跨实例同步）</li>
 * </ul>
 */
@Slf4j
@Component
public class McpConfigStore {

    private final McpNacosProperties props;
    private final ObjectMapper objectMapper;

    private volatile ConfigService configService;
    private volatile NamingService namingService;

    /** 本平台实例地址，作为 Naming 中每个 MCP 服务器实例的 instance ip:port（ephemeral 健康检查基于此）。 */
    private String localIp = "127.0.0.1";
    private int localPort = 8080;

    public void setLocalAddress(String ip, int port) {
        this.localIp = ip;
        this.localPort = port;
    }

    /**
     * 本实例的权威 MCP 目录（内存中为唯一真相源）。所有写操作先改内存再发布完整快照到 Nacos，
     * 避免对 Nacos 的「读-改-写」竞态（Nacos 最终一致，load-modify-publish 会丢失并发变更）。
     * Nacos 仅作为跨实例广播通道，监听回调用远端全量替换本内存。
     */
    private final Map<String, AgentscopeCoreProperties.McpServerConfig> directory = new ConcurrentHashMap<>();

    /** 目录版本号（单调递增），用于丢弃 Nacos 最终一致导致的过期通知，避免本地增删被旧值回灌覆盖。 */
    private final AtomicLong directoryVersion = new AtomicLong(0);

    private final List<Consumer<Map<String, AgentscopeCoreProperties.McpServerConfig>>> changeListeners =
            new CopyOnWriteArrayList<>();

    public McpConfigStore(McpNacosProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    public boolean isEnabled() {
        return props.isEnabled();
    }

    /** 初始化 Nacos 客户端并注册目录监听。需在 Spring 就绪后调用一次。 */
    public void init() {
        if (!props.isEnabled()) {
            log.warn("Nacos MCP 协调底座已禁用（yunxi.mcp.nacos.enabled=false），退化为纯本地静态配置");
            return;
        }
        try {
            Properties p = new Properties();
            p.put("serverAddr", props.getServerAddr());
            if (props.getNamespace() != null && !props.getNamespace().isEmpty()) {
                p.put("namespace", props.getNamespace());
            }
            if (props.getUsername() != null && !props.getUsername().isEmpty()) {
                p.put("username", props.getUsername());
                p.put("password", props.getPassword());
            }
            configService = NacosFactory.createConfigService(p);
            namingService = NacosFactory.createNamingService(p);
            configService.addListener(props.getDataId(), props.getGroup(), new AbstractListener() {
                @Override
                public void receiveConfigInfo(String config) {
                    Snapshot s = parseSnapshot(config);
                    // 丢弃过期通知：Nacos 最终一致，本实例刚发布的通知或旧值回灌都可能以更低版本到达，
                    // 若直接覆盖会回灌已删除的条目、撤销本地意图。仅当版本严格更新时才应用。
                    if (s.version <= directoryVersion.get()) {
                        return;
                    }
                    directoryVersion.set(s.version);
                    directory.clear();
                    directory.putAll(s.servers);
                    for (Consumer<Map<String, AgentscopeCoreProperties.McpServerConfig>> l : changeListeners) {
                        try {
                            l.accept(s.servers);
                        } catch (Exception e) {
                            log.error("MCP 目录变更监听回调执行失败", e);
                        }
                    }
                }
            });
            directory.putAll(readNacos());
            log.info("[MCP] 已从 Nacos 载入目录 {} 个服务器 {}", directory.size(), directory.keySet());
            log.info("Nacos MCP 协调底座已连接：serverAddr={}, dataId={}, group={}",
                    props.getServerAddr(), props.getDataId(), props.getGroup());
        } catch (NacosException e) {
            log.error("Nacos MCP 协调底座初始化失败（不影响 Agent 启动）", e);
        }
    }

    /** 读取当前目录快照（来自本实例内存权威目录，不经过 Nacos 网络读，无竞态）。 */
    public Map<String, AgentscopeCoreProperties.McpServerConfig> loadServers() {
        return new HashMap<>(directory);
    }

    private Map<String, AgentscopeCoreProperties.McpServerConfig> readNacos() {
        if (configService == null) {
            return Map.of();
        }
        try {
            String cfg = configService.getConfig(props.getDataId(), props.getGroup(), 5000);
            Snapshot s = parseSnapshot(cfg);
            directoryVersion.set(Math.max(directoryVersion.get(), s.version));
            return s.servers;
        } catch (NacosException e) {
            log.warn("读取 MCP 目录失败", e);
            return Map.of();
        }
    }

    /** 写入（新增/更新）一个 MCP 服务器到目录（先改内存，再发布完整快照）。 */
    public void saveServer(String name, AgentscopeCoreProperties.McpServerConfig cfg) {
        directory.put(name, cfg);
        publishAll();
    }

    /**
     * 批量同步目录：把 desired（YAML 启用的服务器）合并进本实例内存目录并一次性发布完整快照。
     * 内存为单一真相源，不存在读-改-写竞态。
     */
    public void syncServers(Map<String, AgentscopeCoreProperties.McpServerConfig> desired) {
        if (desired != null) {
            directory.putAll(desired);
        }
        log.info("[MCP] syncServers：内存目录共 {} 个服务器 {}", directory.size(), directory.keySet());
        publishAll();
    }

    /** 从目录移除一个 MCP 服务器（先改内存，再发布完整快照）。 */
    public void removeServer(String name) {
        directory.remove(name);
        publishAll();
    }

    private void publishAll() {
        long v = directoryVersion.incrementAndGet();
        publish(v, new HashMap<>(directory));
    }

    private void publish(long version, Map<String, AgentscopeCoreProperties.McpServerConfig> servers) {
        if (configService == null) {
            throw new IllegalStateException("Nacos 未初始化");
        }
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("version", version);
            root.set("servers", objectMapper.valueToTree(servers));
            boolean ok = configService.publishConfig(props.getDataId(), props.getGroup(),
                    objectMapper.writeValueAsString(root));
            if (!ok) {
                throw new IllegalStateException("发布 MCP 目录到 Nacos 失败");
            }
        } catch (Exception e) {
            throw new IllegalStateException("发布 MCP 目录失败: " + e.getMessage(), e);
        }
    }

    /**
     * 注册一个 MCP 服务器的 Naming 实例（供跨 JVM 服务发现与健康检查）。
     * serviceName = mcp-server-&lt;name&gt;，ephemeral=true（平台实例宕机后自动下线），
     * metadata 携带 server.json 字段（transport/url/command/args/headers/env/timeout/group/enabled）。
     */
    public void registerServerInstance(String name, AgentscopeCoreProperties.McpServerConfig cfg) {
        if (namingService == null) {
            return;
        }
        try {
            Instance instance = new Instance();
            instance.setServiceName(props.getMcpServerServicePrefix() + name);
            instance.setIp(localIp);
            instance.setPort(localPort);
            instance.setEphemeral(true);
            Map<String, String> metadata = new HashMap<>();
            metadata.put("name", name);
            if (cfg.getType() != null) metadata.put("transport", cfg.getType());
            if (cfg.getUrl() != null) metadata.put("url", cfg.getUrl());
            if (cfg.getCommand() != null) metadata.put("command", cfg.getCommand());
            if (cfg.getArgs() != null && !cfg.getArgs().isEmpty()) {
                metadata.put("args", String.join(" ", cfg.getArgs()));
            }
            if (cfg.getHeaders() != null) {
                try {
                    metadata.put("headers", objectMapper.writeValueAsString(cfg.getHeaders()));
                } catch (Exception ignored) {
                }
            }
            if (cfg.getEnv() != null) {
                try {
                    metadata.put("env", objectMapper.writeValueAsString(cfg.getEnv()));
                } catch (Exception ignored) {
                }
            }
            if (cfg.getTimeout() != null) metadata.put("timeout", String.valueOf(cfg.getTimeout()));
            metadata.put("group", name);
            metadata.put("enabled", String.valueOf(cfg.isEnabled()));
            instance.setMetadata(metadata);
            namingService.registerInstance(instance.getServiceName(), instance);
            log.info("[MCP] 已注册 Naming 实例 {}（ephemeral 健康检查）", instance.getServiceName());
        } catch (NacosException e) {
            log.warn("注册 MCP 服务器 Naming 实例失败: {}", name, e);
        }
    }

    /** 注销一个 MCP 服务器的 Naming 实例。 */
    public void deregisterServerInstance(String name) {
        if (namingService == null) {
            return;
        }
        try {
            Instance instance = new Instance();
            instance.setServiceName(props.getMcpServerServicePrefix() + name);
            instance.setIp(localIp);
            instance.setPort(localPort);
            instance.setEphemeral(true);
            namingService.deregisterInstance(instance.getServiceName(), instance);
            log.info("[MCP] 已注销 Naming 实例 {}", instance.getServiceName());
        } catch (NacosException e) {
            log.warn("注销 MCP 服务器 Naming 实例失败: {}", name, e);
        }
    }

    /**
     * 从 Nacos Naming 列出全部 MCP 服务器实例（GET /api/mcp/servers 接口数据源）。
     * 返回结构对齐官方 server.json 规范（含 name/transport/url/status 等）。
     * Naming 未启用时降级到内存目录。
     */
    public List<McpServerEntry> listServerEntries() {
        List<McpServerEntry> result = new ArrayList<>();
        String prefix = props.getMcpServerServicePrefix();
        if (namingService == null) {
            // Nacos 未启用：从内存目录（权威）构造，无健康探测
            for (Map.Entry<String, AgentscopeCoreProperties.McpServerConfig> en : directory.entrySet()) {
                result.add(toEntry(en.getKey(), en.getValue(), "local", null));
            }
            return result;
        }
        // 以内存目录（权威 name 集合）为基础，从 Nacos Naming 取每个实例健康状态。
        // 规避 getServicesOfServer/ListView 在不同 Nacos 客户端版本的 API 差异，统一用稳定的 getAllInstances。
        for (Map.Entry<String, AgentscopeCoreProperties.McpServerConfig> en : directory.entrySet()) {
            String name = en.getKey();
            try {
                List<Instance> instances = namingService.getAllInstances(prefix + name);
                Instance inst = instances.stream().filter(Instance::isHealthy)
                        .findFirst().orElse(instances.isEmpty() ? null : instances.get(0));
                if (inst == null) {
                    result.add(toEntry(name, en.getValue(), "UNKNOWN", null));
                } else {
                    result.add(toEntry(name, inst));
                }
            } catch (NacosException ex) {
                result.add(toEntry(name, en.getValue(), "UNKNOWN", null));
            }
        }
        return result;
    }

    private McpServerEntry toEntry(String name, Instance inst) {
        Map<String, String> md = inst.getMetadata();
        McpServerEntry entry = new McpServerEntry();
        entry.setName(name);
        entry.setTransport(md.getOrDefault("transport", md.get("type")));
        entry.setUrl(md.get("url"));
        entry.setCommand(md.get("command"));
        entry.setArgs(md.get("args"));
        entry.setHeaders(md.get("headers"));
        entry.setEnv(md.get("env"));
        entry.setTimeout(md.get("timeout"));
        entry.setGroup(md.getOrDefault("group", name));
        entry.setEnabled(Boolean.parseBoolean(md.getOrDefault("enabled", "true")));
        entry.setStatus(inst.isHealthy() ? "UP" : "DOWN");
        entry.setVersion(null);
        return entry;
    }

    private McpServerEntry toEntry(String name, AgentscopeCoreProperties.McpServerConfig cfg,
            String status, String version) {
        McpServerEntry entry = new McpServerEntry();
        entry.setName(name);
        entry.setTransport(cfg.getType());
        entry.setUrl(cfg.getUrl());
        entry.setCommand(cfg.getCommand());
        entry.setArgs(cfg.getArgs() == null ? null : String.join(" ", cfg.getArgs()));
        try {
            entry.setHeaders(cfg.getHeaders() == null ? null : objectMapper.writeValueAsString(cfg.getHeaders()));
            entry.setEnv(cfg.getEnv() == null ? null : objectMapper.writeValueAsString(cfg.getEnv()));
        } catch (Exception ignored) {
        }
        entry.setTimeout(cfg.getTimeout() == null ? null : String.valueOf(cfg.getTimeout()));
        entry.setGroup(name);
        entry.setEnabled(cfg.isEnabled());
        entry.setStatus(status);
        entry.setVersion(version);
        return entry;
    }

    /** 注册协调实例（跨 JVM 广播感知）。 */
    public void registerCoordinatorInstance(String ip, int port) {
        if (namingService == null) {
            return;
        }
        try {
            namingService.registerInstance(props.getServiceName(), ip, port);
        } catch (NacosException e) {
            log.warn("注册 MCP 协调实例失败", e);
        }
    }

    public void deregisterCoordinatorInstance(String ip, int port) {
        if (namingService == null) {
            return;
        }
        try {
            namingService.deregisterInstance(props.getServiceName(), ip, port);
        } catch (NacosException e) {
            log.warn("注销 MCP 协调实例失败", e);
        }
    }

    /** 注册目录变更监听（跨实例同步入口）。 */
    public void addServerChangeListener(Consumer<Map<String, AgentscopeCoreProperties.McpServerConfig>> listener) {
        changeListeners.add(listener);
    }

    /** 目录快照：版本号 + 服务器映射。 */
    private static final class Snapshot {
        final long version;
        final Map<String, AgentscopeCoreProperties.McpServerConfig> servers;
        Snapshot(long version, Map<String, AgentscopeCoreProperties.McpServerConfig> servers) {
            this.version = version;
            this.servers = servers;
        }
    }

    private Snapshot parseSnapshot(String config) {
        if (config == null || config.isBlank()) {
            return new Snapshot(0, Map.of());
        }
        try {
            JsonNode root = objectMapper.readTree(config);
            long version = 0;
            JsonNode vNode = root.get("version");
            if (vNode != null && !vNode.isNull()) {
                version = vNode.asLong();
            }
            JsonNode servers = root.get("servers");
            if (servers == null || servers.isNull()) {
                return new Snapshot(version, Map.of());
            }
            Map<String, AgentscopeCoreProperties.McpServerConfig> map = objectMapper.convertValue(servers,
                    new TypeReference<Map<String, AgentscopeCoreProperties.McpServerConfig>>() {});
            return new Snapshot(version, map);
        } catch (Exception e) {
            log.error("解析 MCP 目录 JSON 失败", e);
            return new Snapshot(0, Map.of());
        }
    }
}
