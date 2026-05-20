package io.yunxi.platform.gateway.channel.wecom;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 企业微信配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "gateway.platforms.wecom")
public class WeComProperties {

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
