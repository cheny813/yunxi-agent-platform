package io.yunxi.platform.gateway.channel.webapi;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Web API 配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "gateway.platforms.webapi")
public class WebApiProperties {

    /** 是否启用 */
    private boolean enabled = false;

    /** API Token（用于鉴权，空=不鉴权） */
    private String apiToken;
}
