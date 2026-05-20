package io.yunxi.platform.business.a2apipeline.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * A2ACodePipelineProperties A2A代码流水线配置
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "a2a.pipeline")
public class A2ACodePipelineProperties {

    /**
     * 是否启用流水线
     */
    private boolean enabled = true;

    /**
     * 最大并发流水线数
     */
    private int maxConcurrentPipelines = 10;

    /**
     * 默认超时时间(秒)
     */
    private int defaultTimeoutSeconds = 600;

    /**
     * 重试次数
     */
    private int maxRetries = 3;

    /**
     * 重试延迟(毫秒)
     */
    private long retryDelayMs = 1000;

    /**
     * 是否启用健康检查
     */
    private boolean enableHealthCheck = true;

    /**
     * 健康检查间隔(秒)
     */
    private int healthCheckIntervalSeconds = 30;
}