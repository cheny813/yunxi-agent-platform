package io.yunxi.platform.config;

import io.yunxi.platform.shared.constants.ConfigDefaults;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * A2A（Agent-to-Agent）协议配置。
 *
 * <p>统一配置键前缀为 {@code yunxi.a2a}（yunxi 产品平面开关，区别于框架扩展命名空间）。
 * {@code yunxi.a2a.enabled} 为 A2A 能力总开关，控制 A2AServer REST 端点装配与 A2A 注册中心校验；
 * 注册中心地址 / 命名空间为启用时的必填项。客户端调用超时可经 {@code yunxi.a2a.timeout-seconds} 覆盖。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "yunxi.a2a")
public class A2AConfig {

    /** 是否启用 A2A（总开关） */
    private boolean enabled = false;

    /** 注册中心类型：nacos、consul、static */
    private String registryType = ConfigDefaults.DEFAULT_REGISTRY_TYPE;

    /** 注册中心地址（启用时必填，无默认值） */
    private String registryAddr;

    /** 注册中心命名空间（启用时必填，无默认值） */
    private String namespace;
}
