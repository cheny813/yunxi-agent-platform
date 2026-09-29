package io.yunxi.platform.channel;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IM 渠道配置绑定（根前缀 {@code yunxi.channels}），对应 {@code config/channels.yml}。
 *
 * <p>设计边界（避免双份真相）：本配置只描述「渠道开关 + 渠道类型 + 默认路由 Agent + 渠道凭据」，
 * 不引用任何在线 Agent 实例、不重复声明 Agent 的模型/工具/middleware。Agent 真相源仍是
 * {@code AgentConfigurer} 构建并注册到 {@code AgentService} 的在线 {@code HarnessAgent}，
 * 由 {@code ChannelAutoConfiguration} 经 {@code AgentService.getAgentInstance} 注册到框架
 * {@code GatewayBootstrap}，使 IM 渠道自动继承压缩/画像/权限/技能等全部能力。</p>
 */
@ConfigurationProperties(prefix = "yunxi.channels")
public class ChannelsProperties {

    /** 总开关，默认关闭（避免意外把 Agent 暴露到公网 IM 平台） */
    private boolean enabled = false;

    /** 主 Agent（mainAgent），不配则取 listAgents 第一个 */
    private String defaultAgentId;

    /** 各 IM 渠道定义，key 为渠道逻辑名（如 dingtalk/feishu） */
    private Map<String, ChannelItem> definitions = new LinkedHashMap<>();

    public static class ChannelItem {
        /** 单体渠道开关 */
        private boolean enabled = false;
        /** 框架 ChannelConfig.channelId（渠道内部路由标识，通常等于逻辑名） */
        private String channelId;
        /** 渠道类型：dingtalk/feishu/wecom/github/gitlab，决定调用哪个 Channel.fromProperties 工厂 */
        private String type;
        /** 该渠道默认路由到的 yunxi Agent 名（对应 AgentService 注册的实例名），不配则用 defaultAgentId */
        private String defaultAgentId;
        /** 渠道凭据与连接参数（appKey/appSecret/robotCode/apiBase 等），透传给框架 Channel.fromProperties */
        private Map<String, Object> props = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getChannelId() {
            return channelId;
        }

        public void setChannelId(String channelId) {
            this.channelId = channelId;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getDefaultAgentId() {
            return defaultAgentId;
        }

        public void setDefaultAgentId(String defaultAgentId) {
            this.defaultAgentId = defaultAgentId;
        }

        public Map<String, Object> getProps() {
            return props;
        }

        public void setProps(Map<String, Object> props) {
            this.props = props;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultAgentId() {
        return defaultAgentId;
    }

    public void setDefaultAgentId(String defaultAgentId) {
        this.defaultAgentId = defaultAgentId;
    }

    public Map<String, ChannelItem> getDefinitions() {
        return definitions;
    }

    public void setDefinitions(Map<String, ChannelItem> definitions) {
        this.definitions = definitions;
    }
}
