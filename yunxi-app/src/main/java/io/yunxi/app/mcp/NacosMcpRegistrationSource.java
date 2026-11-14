package io.yunxi.app.mcp;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.yunxi.capability.McpDirectory;
import io.yunxi.capability.McpServerSpec;
import io.yunxi.runtime.Json;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 应用平面。MCP 动态注册的 Nacos 协调底座实现。负责：
 *
 * <ul>
 *   <li>启动时从 Nacos {@code dataId} 读取全量目录快照并同步进内存权威目录 {@link McpDirectory}；</li>
 *   <li>注册 Nacos 配置监听器，目录内容变更时实时对齐；</li>
 *   <li>运行时 {@code register/unregister} 先更新内存目录、再发布全量快照回 Nacos（解决多实例读-改-写竞态）；</li>
 *   <li>Nacos 未启用或不可达时优雅降级：使用本地 {@code staticServers} 种子（若有），动态注册接口仍可用。</li>
 * </ul>
 *
 * <p>与文档《06. 配置指南》一致：内存权威目录 + Nacos 全量快照。Agent 构建期按名从目录解析，无需写死连接。
 */
@Component
public class NacosMcpRegistrationSource {

    private static final Logger log = LoggerFactory.getLogger(NacosMcpRegistrationSource.class);

    private final McpDirectory mcpDirectory;
    private final McpNacosProperties props;
    private final ObjectMapper objectMapper = Json.objectMapper();

    private volatile ConfigService configService;

    /** 注入内存权威目录与 Nacos 协调底座配置。 */
    public NacosMcpRegistrationSource(McpDirectory mcpDirectory, McpNacosProperties props) {
        this.mcpDirectory = mcpDirectory;
        this.props = props;
    }

    /** 启动期初始化：未启用 Nacos 则退化为本地种子目录；启用则连 Nacos 读全量快照并挂变更监听（不可达降级）。 */
    @PostConstruct
    public void init() {
        if (!props.isEnabled()) {
            log.warn("[MCP] Nacos 协调底座未启用，退化为本地内存目录（重启清空）。"
                    + " 可通过 /api/mcp/servers 运行时注册，或配置 yunxi.mcp.nacos.enabled=true 接入 Nacos。");
            seedLocalServers();
            return;
        }
        try {
            Properties p = new Properties();
            p.setProperty("serverAddr", props.getServerAddr());
            if (props.getNamespace() != null && !props.getNamespace().isBlank()) {
                p.setProperty("namespace", props.getNamespace());
            }
            configService = NacosFactory.createConfigService(p);
            String content = configService.getConfig(props.getDataId(), props.getGroup(), 5000);
            syncFromContent(content);
            configService.addListener(props.getDataId(), props.getGroup(), new Listener() {
                @Override
                public Executor getExecutor() {
                    return Executors.newSingleThreadExecutor();
                }

                @Override
                public void receiveConfigInfo(String cfg) {
                    syncFromContent(cfg);
                }
            });
            log.info("[MCP] 已接入 Nacos 协调底座 {}，监听 dataId={} group={}",
                    props.getServerAddr(), props.getDataId(), props.getGroup());
        } catch (NacosException | RuntimeException e) {
            log.error("[MCP] Nacos 不可达或初始化失败，退化为本地内存目录：{}", e.getMessage());
            configService = null;
            seedLocalServers();
        }
    }

    /** 把配置中的本地种子服务器（enabled 者）写入内存目录，作为 Nacos 未启用时的初始目录。 */
    private void seedLocalServers() {
        List<McpServerConfig> seeds = props.getStaticServers();
        if (seeds == null || seeds.isEmpty()) {
            return;
        }
        for (McpServerConfig c : seeds) {
            if (c == null || c.getName() == null || !Boolean.TRUE.equals(c.getEnabled())) {
                continue;
            }
            mcpDirectory.register(c.toSpec());
        }
        log.info("[MCP] 本地种子服务器：{}", mcpDirectory.all().stream().map(McpServerSpec::name).toList());
    }

    /** 把 Nacos 快照内容对齐进内存目录（启用项注册、禁用/缺失项移除），使目录成为 Nacos 全量快照的镜像。 */
    private void syncFromContent(String content) {
        List<McpServerConfig> desired = parse(content);
        Set<String> desiredNames = new HashSet<>();
        for (McpServerConfig c : desired) {
            if (c == null || c.getName() == null) {
                continue;
            }
            if (!Boolean.TRUE.equals(c.getEnabled())) {
                mcpDirectory.unregister(c.getName());
                continue;
            }
            mcpDirectory.register(c.toSpec());
            desiredNames.add(c.getName());
        }
        // 移除目录中不再出现在快照里的条目（内存权威目录 + Nacos 全量快照）
        for (McpServerSpec existing : mcpDirectory.all()) {
            if (!desiredNames.contains(existing.name())) {
                mcpDirectory.unregister(existing.name());
            }
        }
        log.info("[MCP] 目录已与 Nacos 快照对齐，当前服务器：{}",
                mcpDirectory.all().stream().map(McpServerSpec::name).toList());
    }

    /** 解析快照 JSON 为配置列表；内容空或解析失败返回空列表（失败已记日志）。 */
    private List<McpServerConfig> parse(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(content, new TypeReference<List<McpServerConfig>>() {});
        } catch (Exception e) {
            log.error("[MCP] 解析 Nacos 目录快照失败：{}", e.getMessage());
            return List.of();
        }
    }

    /** 运行时注册一个服务器（供 REST API 调用），更新内存目录并回写全量快照到 Nacos。 */
    public synchronized void register(McpServerConfig cfg) {
        if (cfg == null || cfg.getName() == null) {
            throw new IllegalArgumentException("MCP 服务器 name 不能为空");
        }
        if (cfg.getEnabled() == null) {
            cfg.setEnabled(true);
        }
        mcpDirectory.register(cfg.toSpec());
        publishSnapshot();
    }

    /** 运行时注销一个服务器（供 REST API 调用），更新内存目录并回写全量快照到 Nacos。 */
    public synchronized void unregister(String name) {
        mcpDirectory.unregister(name);
        publishSnapshot();
    }

    private void publishSnapshot() {
        ConfigService cs = configService;
        if (cs == null) {
            return;
        }
        try {
            List<McpServerConfig> snapshot = new ArrayList<>();
            for (McpServerSpec s : mcpDirectory.all()) {
                snapshot.add(McpServerConfig.fromSpec(s));
            }
            cs.publishConfig(props.getDataId(), props.getGroup(), objectMapper.writeValueAsString(snapshot));
        } catch (Exception e) {
            log.error("[MCP] 发布全量快照到 Nacos 失败：{}", e.getMessage());
        }
    }

    /** 返回内存目录当前全部条目，并标托管状态（已接 Nacos 为 UP，否则 local）。 */
    public List<McpServerConfig> currentEntries() {
        List<McpServerConfig> list = new ArrayList<>();
        for (McpServerSpec s : mcpDirectory.all()) {
            McpServerConfig c = McpServerConfig.fromSpec(s);
            c.setStatus(configService != null ? "UP" : "local");
            list.add(c);
        }
        return list;
    }

    /** 当前是否被 Nacos 托管（用于状态展示）。 */
    public boolean isNacosManaged() {
        return configService != null;
    }
}
