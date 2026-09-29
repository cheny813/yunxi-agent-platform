package io.yunxi.platform.channel;

import io.agentscope.extensions.channel.dingtalk.DingTalkChannel;
import io.agentscope.extensions.channel.feishu.FeishuChannel;
import io.agentscope.extensions.channel.github.GitHubChannel;
import io.agentscope.extensions.channel.gitlab.GitLabChannel;
import io.agentscope.extensions.channel.wecom.WeComChannel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.GatewayBootstrap;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.yunxi.platform.agent.service.AgentService;
import io.yunxi.platform.shared.dto.AgentInfoDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * IM 渠道自动装配（正交工作，与 REST 入口 agent-ingress 并列，复用同一在线 Agent 真相源）。
 *
 * <p>设计边界（避免双份真相，已源码核实）：
 * <ul>
 *   <li>框架 {@code GatewayBootstrap.builder().agent(id, harnessAgent)} 注册的是固定 {@code HarnessAgent} 实例，
 *       且框架 Gateway 持有该实例并串行化同一会话的 turns；这与 yunxi REST 路径「每次 getAgentInstance 取新实例」
 *       的模型不同。本配置直接复用 yunxi 既有的在线 Agent（经 {@code AgentService.getAgentInstance} 取得的实例
 *       由 {@code AgentConfigurer} 装配了压缩/画像/权限/技能等完整 middleware），使 IM 渠道自动继承全部能力，
 *       不重复构建 Agent 真相源。</li>
 *   <li>多租户隔离由 {@code HarnessAgent.workspaceFor(userId, sessionId)} 按 RuntimeContext 承载，与 REST 路径一致；
 *       框架 Channel 从 inboundMessage 解析 userId/sessionId，无需 yunxi 自定义 resolver。</li>
 *   <li>单个渠道注册失败只记日志、不阻断启动，也不影响其他渠道与 REST 入口。</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(prefix = "yunxi.channels", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ChannelsProperties.class)
public class ChannelAutoConfiguration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ChannelAutoConfiguration.class);

    private final AgentService agentService;
    private final ChannelsProperties properties;
    private final ChannelStatusRegistry channelStatusRegistry;

    public ChannelAutoConfiguration(AgentService agentService, ChannelsProperties properties,
            ChannelStatusRegistry channelStatusRegistry) {
        this.agentService = agentService;
        this.properties = properties;
        this.channelStatusRegistry = channelStatusRegistry;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<AgentInfoDto> agents = agentService.listAgents();
        if (agents.isEmpty()) {
            log.warn("[channels] 未注册任何在线 Agent，IM 渠道不启动");
            return;
        }
        String mainAgent = properties.getDefaultAgentId() != null
                ? properties.getDefaultAgentId() : agents.get(0).getName();

        GatewayBootstrap.Builder builder = GatewayBootstrap.builder();
        for (AgentInfoDto agent : agents) {
            // 复用 yunxi 在线 Agent 真相源（含完整 middleware/toolkit/skills），注册为固定实例到框架 Gateway。
            builder.agent(agent.getName(), (HarnessAgent) agentService.getAgentInstance(agent.getName()));
        }
        builder.mainAgent(mainAgent);

        int registered = 0;
        List<String> startedChannelIds = new java.util.ArrayList<>();
        for (Map.Entry<String, ChannelsProperties.ChannelItem> entry : properties.getDefinitions().entrySet()) {
            ChannelsProperties.ChannelItem item = entry.getValue();
            if (!item.isEnabled()) {
                continue;
            }
            try {
                String channelId = item.getChannelId() != null ? item.getChannelId() : entry.getKey();
                String targetAgent = item.getDefaultAgentId() != null ? item.getDefaultAgentId() : mainAgent;
                ChannelConfig routing = ChannelConfig.builder(channelId).defaultAgentId(targetAgent).build();
                Channel channel = buildChannel(item.getType(), channelId, routing, item.getProps());
                if (channel != null) {
                    builder.channel(channel);
                    registered++;
                    startedChannelIds.add(channelId);
                    channelStatusRegistry.register(channelId, item.getType(), targetAgent);
                    log.info("[channels] 已注册 IM 渠道 {} -> 路由 Agent={}", channelId, targetAgent);
                }
            } catch (Exception ex) {
                log.error("[channels] 注册渠道 {} 失败（不影响其他渠道与启动）", entry.getKey(), ex);
            }
        }

        if (registered == 0) {
            log.info("[channels] 总开关开启但无启用渠道，IM 渠道不启动");
            return;
        }
        GatewayBootstrap bootstrap = builder.build();
        bootstrap.start();
        startedChannelIds.forEach(channelStatusRegistry::markStarted);
        log.info("[channels] IM 渠道已启动，主 Agent={}，共注册 {} 个渠道", mainAgent, registered);
    }

    private Channel buildChannel(String type, String channelId, ChannelConfig routing, Map<String, Object> props) {
        if (type == null) {
            return null;
        }
        switch (type) {
            case "dingtalk":
                return DingTalkChannel.fromProperties(channelId, routing, props);
            case "feishu":
                return FeishuChannel.fromProperties(channelId, routing, props);
            case "wecom":
                return WeComChannel.fromProperties(channelId, routing, props);
            case "github":
                return GitHubChannel.fromProperties(channelId, routing, props);
            case "gitlab":
                return GitLabChannel.fromProperties(channelId, routing, props);
            default:
                log.warn("[channels] 未知渠道类型 {}，跳过", type);
                return null;
        }
    }
}
