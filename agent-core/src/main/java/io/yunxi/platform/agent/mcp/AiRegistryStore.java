package io.yunxi.platform.agent.mcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.yunxi.platform.shared.config.McpNacosProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Nacos 3.2 AI Registry 接入封装（复用 MCP 协调底座同一 Nacos 实例发布/订阅 AI 资源）。
 * <p>Skill / Agent / Prompt / AgentSpec 统一治理，复用与 MCP 协调底座同一 Nacos 地址与命名空间，
 * 落点使用 Nacos ConfigService 以 AI Registry 约定 schema 发布
 * （dataId = prefix.&lt;type&gt;.&lt;name&gt;，group = aiRegistryGroup），与 MCP 注册中心
 * 同集群、同控制台、同命名空间。</p>
 * <p>待 Nacos 官方 Java 发布 SDK（nacos-aisdk AiMaintenanceService）稳定可用后，可平滑替换为原生
 * AI Registry API，本封装的 publish/get/remove/list 接口保持不变。</p>
 */
@Slf4j
@Component
public class AiRegistryStore {

    /** AI 资源类型枚举（Skill / Agent / Prompt / AgentSpec）。 */
    public enum AiType {
        SKILL, AGENT, PROMPT, AGENTSPEC
    }

    private static final String INDEX_NAME = "index";

    private final McpNacosProperties props;
    private final ObjectMapper objectMapper;
    private volatile ConfigService configService;

    /** 本实例已发布 AI 资源的本地缓存，使 get() 立即可用（规避 Nacos 3.x 读后写延迟）。 */
    private final Map<String, String> contentCache = new ConcurrentHashMap<>();

    public AiRegistryStore(McpNacosProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /** Spring 就绪后连接 Nacos（复用 MCP 协调底座同一地址/命名空间）。 */
    @PostConstruct
    public void init() {
        if (!props.isEnabled()) {
            log.warn("Nacos AI Registry 未启用（yunxi.mcp.nacos.enabled=false），跳过初始化");
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
            log.info("[AI-Registry] 已连接 Nacos：serverAddr={}, group={}",
                    props.getServerAddr(), props.getAiRegistryGroup());
        } catch (NacosException e) {
            log.error("Nacos AI Registry 初始化失败（不影响 Agent 启动）", e);
        }
    }

    private String dataId(AiType type, String name) {
        return props.getAiRegistryDataIdPrefix() + "." + type.name().toLowerCase() + "." + name;
    }

    private String indexDataId() {
        return props.getAiRegistryDataIdPrefix() + "." + INDEX_NAME;
    }

    /** 发布/更新一个 AI 资源到 Nacos AI Registry。 */
    public void publish(AiType type, String name, String content) {
        if (configService == null) {
            throw new IllegalStateException("Nacos AI Registry 未初始化");
        }
        try {
            boolean ok = configService.publishConfig(dataId(type, name), props.getAiRegistryGroup(), content);
            if (!ok) {
                throw new IllegalStateException("发布 AI 资源失败: " + type + "/" + name);
            }
            appendIndex(type, name);
            contentCache.put(type.name().toLowerCase() + "/" + name, content);
            log.info("[AI-Registry] 发布 {}: {}", type, name);
        } catch (NacosException e) {
            throw new IllegalStateException("发布 AI 资源失败: " + e.getMessage(), e);
        }
    }

    /** 查询一个 AI 资源内容。 */
    public String get(AiType type, String name) {
        String key = type.name().toLowerCase() + "/" + name;
        // 优先返回本实例刚发布的本地缓存，规避 Nacos 3.x 读后写延迟（刚发布约 3s 内 getConfig 读不到）
        String cached = contentCache.get(key);
        if (cached != null) {
            return cached;
        }
        if (configService == null) {
            return null;
        }
        try {
            return configService.getConfig(dataId(type, name), props.getAiRegistryGroup(), 5000);
        } catch (NacosException e) {
            log.warn("读取 AI 资源失败 {}:{}", type, name, e);
            return null;
        }
    }

    /** 删除一个 AI 资源。 */
    public void remove(AiType type, String name) {
        if (configService == null) {
            return;
        }
        try {
            configService.removeConfig(dataId(type, name), props.getAiRegistryGroup());
            contentCache.remove(type.name().toLowerCase() + "/" + name);
            removeIndex(type, name);
            log.info("[AI-Registry] 移除 {}: {}", type, name);
        } catch (NacosException e) {
            log.warn("移除 AI 资源失败 {}:{}", type, name, e);
        }
    }

    /** 列出全部已发布 AI 资源（type+name 索引）。 */
    public List<Map<String, String>> list() {
        if (configService == null) {
            return List.of();
        }
        try {
            String idx = configService.getConfig(indexDataId(), props.getAiRegistryGroup(), 5000);
            if (idx == null || idx.isBlank()) {
                return List.of();
            }
            return objectMapper.readValue(idx, new TypeReference<List<Map<String, String>>>() {});
        } catch (Exception e) {
            log.warn("读取 AI Registry 索引失败", e);
            return List.of();
        }
    }

    private void appendIndex(AiType type, String name) {
        try {
            List<Map<String, String>> all = new ArrayList<>();
            String idx = configService.getConfig(indexDataId(), props.getAiRegistryGroup(), 5000);
            if (idx != null && !idx.isBlank()) {
                all = objectMapper.readValue(idx, new TypeReference<List<Map<String, String>>>() {});
            }
            boolean exists = all.stream()
                    .anyMatch(m -> type.name().equals(m.get("type")) && name.equals(m.get("name")));
            if (!exists) {
                Map<String, String> e = new HashMap<>();
                e.put("type", type.name());
                e.put("name", name);
                all.add(e);
                configService.publishConfig(indexDataId(), props.getAiRegistryGroup(),
                        objectMapper.writeValueAsString(all));
            }
        } catch (Exception e) {
            log.warn("更新 AI Registry 索引失败", e);
        }
    }

    private void removeIndex(AiType type, String name) {
        try {
            String idx = configService.getConfig(indexDataId(), props.getAiRegistryGroup(), 5000);
            if (idx == null || idx.isBlank()) {
                return;
            }
            List<Map<String, String>> all = objectMapper.readValue(idx,
                    new TypeReference<List<Map<String, String>>>() {});
            all.removeIf(m -> type.name().equals(m.get("type")) && name.equals(m.get("name")));
            configService.publishConfig(indexDataId(), props.getAiRegistryGroup(),
                    objectMapper.writeValueAsString(all));
        } catch (Exception e) {
            log.warn("更新 AI Registry 索引失败", e);
        }
    }
}
