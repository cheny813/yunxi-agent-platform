package io.yunxi.platform.business.a2apipeline.model;

import lombok.Builder;
import lombok.Data;

/**
 * RetryStrategy 重试策略
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
public class RetryStrategy {

    /**
     * 从哪个阶段重新执行
     */
    private String retryFromStage;

    /**
     * 最大重试次数
     */
    private int maxRetries;

    /**
     * 失败后是否升级处理
     */
    private boolean escalateOnFailure;

    /**
     * 重试延迟(毫秒)
     */
    private long retryDelayMs;

    /**
     * 创建默认重试策略
     *
     * @return 默认重试策略实例（从 CODE_FIX 阶段重试，最多2次）
     */
    public static RetryStrategy defaultStrategy() {
        return RetryStrategy.builder()
                .retryFromStage("CODE_FIX")
                .maxRetries(2)
                .escalateOnFailure(true)
                .retryDelayMs(1000)
                .build();
    }
}