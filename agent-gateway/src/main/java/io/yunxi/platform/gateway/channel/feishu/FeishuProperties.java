package io.yunxi.platform.gateway.channel.feishu;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 飞书配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "gateway.platforms.feishu")
public class FeishuProperties {

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
