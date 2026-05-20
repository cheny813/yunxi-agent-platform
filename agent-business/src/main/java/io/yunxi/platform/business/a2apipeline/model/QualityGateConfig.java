package io.yunxi.platform.business.a2apipeline.model;

import lombok.Builder;
import lombok.Data;

/**
 * QualityGateConfig 质量门禁配置
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
public class QualityGateConfig {

    /**
     * 最大审查迭代次数
     */
    private int maxReviewIterations;

    /**
     * 最小测试覆盖率
     */
    private double minTestCoverage;

    /**
     * 是否需要安全扫描
     */
    private boolean requireSecurityScan;

    /**
     * 是否自动部署
     */
    private boolean autoDeploy;

    /**
     * 创建默认配置
     *
     * @return 默认的质量门禁配置实例
     */
    public static QualityGateConfig defaultConfig() {
        return QualityGateConfig.builder()
                .maxReviewIterations(3)
                .minTestCoverage(80.0)
                .requireSecurityScan(true)
                .autoDeploy(false)
                .build();
    }
}