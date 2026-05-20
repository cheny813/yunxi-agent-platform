package io.yunxi.platform.gateway.channel.dingtalk;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 钉钉配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "gateway.platforms.dingtalk")
public class DingTalkProperties {

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
