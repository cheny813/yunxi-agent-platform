package io.yunxi.platform.shared.config;

import lombok.Data;

/**
 * 节点重试配置。
 */
@Data
public class RetryConfig {

    /** 最大尝试次数 */
    private Integer maxAttempts;

    /** 退避秒数 */
    private Integer backoffSeconds;
}
