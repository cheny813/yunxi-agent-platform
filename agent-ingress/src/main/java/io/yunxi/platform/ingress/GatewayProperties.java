package io.yunxi.platform.ingress;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 统一接入网关的运行期配置。
 *
 * <p>集中承载边缘横切关注点（API 基路径），使这些配置不再散落在各 Controller 内部。
 * a2a / aistio 开关沿用 {@code yunxi.a2a.enabled} / {@code yunxi.aistio.enabled} 既有键，
 * 由 {@code GatewayHealthController} 经 {@code @Value} 读取，本类只负责网关自身语义。</p>
 */
@ConfigurationProperties(prefix = "yunxi.gateway")
public class GatewayProperties {

    /** 对外归一化 API 基路径。 */
    private String apiBasePath = "/api/v1";

    public String getApiBasePath() {
        return apiBasePath;
    }

    public void setApiBasePath(String apiBasePath) {
        this.apiBasePath = apiBasePath;
    }
}
