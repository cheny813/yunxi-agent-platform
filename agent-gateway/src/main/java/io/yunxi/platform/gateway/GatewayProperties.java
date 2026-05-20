package io.yunxi.platform.gateway;

import io.yunxi.platform.gateway.model.SessionResetPolicy;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关配置属性
 *
 * <p>绑定 gateway.* 配置项</p>
 */
@Data
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    /** agent-core 服务地址 */
    private String coreUrl = "";

    /** 默认 Agent 名称 */
    private String defaultAgent = "coding-assistant";

    /** 会话配置 */
    private SessionConfig session = new SessionConfig();

    /** 并发控制配置 */
    private ConcurrencyConfig concurrency = new ConcurrencyConfig();

    /** 流式响应配置 */
    private StreamingConfig streaming = new StreamingConfig();

    /** 平台配置 */
    private PlatformsConfig platforms = new PlatformsConfig();

    @Data
    public static class SessionConfig {
        /** 会话重置策略 */
        private SessionResetPolicy resetPolicy = SessionResetPolicy.BOTH;

        /** 每日重置时间（小时，0-23） */
        private int dailyResetHour = 4;

        /** 空闲超时（分钟） */
        private long idleTimeoutMinutes = 1440;

        /** 存储类型：memory / sqlite */
        private String storeType = "memory";
    }

    @Data
    public static class ConcurrencyConfig {
        /** 每个平台最大并发请求数 */
        private int maxConcurrentRequests = 10;

        /** 单次请求超时（秒） */
        private int requestTimeoutSeconds = 300;
    }

    @Data
    public static class StreamingConfig {
        /** SSE chunk 合并阈值（毫秒） */
        private long chunkMergeThreshold = 500;

        /** 是否发送"思考中"状态 */
        private boolean typingIndicator = true;
    }

    @Data
    public static class PlatformsConfig {
        private WeComConfig wecom = new WeComConfig();
        private DingTalkConfig dingtalk = new DingTalkConfig();
        private FeishuConfig feishu = new FeishuConfig();
        private WebApiConfig webapi = new WebApiConfig();
    }

    @Data
    public static class WeComConfig {
        /** 是否启用 */
        private boolean enabled = false;

        /** 机器人 ID */
        private String botId;

        /** 机器人密钥 */
        private String secret;

        /** WebSocket 地址 */
        private String wsUrl = "wss://openws.work.weixin.qq.com";

        /** 重连退避时间（秒） */
        private List<Integer> reconnectBackoff = List.of(2, 5, 10, 30, 60);

        /** 单条消息最大长度 */
        private int maxMessageLength = 2048;

        /** 允许的用户白名单（空=允许所有） */
        private List<String> allowedUsers = new ArrayList<>();

        /** 允许的群聊白名单（空=允许所有） */
        private List<String> allowedChats = new ArrayList<>();
    }

    @Data
    public static class DingTalkConfig {
        /** 是否启用 */
        private boolean enabled = false;

        /** Stream模式客户端ID */
        private String clientId;

        /** Stream模式客户端密钥 */
        private String clientSecret;

        /** 允许的用户白名单 */
        private List<String> allowedUsers = new ArrayList<>();

        /** 允许的群聊白名单 */
        private List<String> allowedChats = new ArrayList<>();
    }

    @Data
    public static class FeishuConfig {
        /** 是否启用 */
        private boolean enabled = false;

        /** 应用 ID */
        private String appId;

        /** 应用密钥 */
        private String appSecret;

        /** 传输方式：websocket / webhook */
        private String transport = "websocket";

        /** 允许的用户白名单 */
        private List<String> allowedUsers = new ArrayList<>();
    }

    @Data
    public static class WebApiConfig {
        /** 是否启用 */
        private boolean enabled = false;

        /** API Token（用于鉴权，空=不鉴权） */
        private String apiToken;
    }
}
