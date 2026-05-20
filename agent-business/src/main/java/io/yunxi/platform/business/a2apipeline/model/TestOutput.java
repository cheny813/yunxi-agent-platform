package io.yunxi.platform.business.a2apipeline.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * TestOutput 测试输出
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
public class TestOutput {

    /**
     * 是否所有测试通过
     */
    private boolean allPassed;

    /**
     * 测试统计
     */
    private Map<String, Integer> testStats;

    /**
     * 覆盖率
     */
    private double coveragePercent;

    /**
     * 执行时间(毫秒)
     */
    private long executionTimeMs;

    /**
     * 创建通过输出
     *
     * @param coveragePercent 测试覆盖率
     * @return 测试通过的输出实例
     */
    public static TestOutput passed(double coveragePercent) {
        return TestOutput.builder()
                .allPassed(true)
                .coveragePercent(coveragePercent)
                .build();
    }

    /**
     * 创建失败输出
     *
     * @return 测试失败的输出实例
     */
    public static TestOutput failed() {
        return TestOutput.builder()
                .allPassed(false)
                .coveragePercent(0.0)
                .build();
    }
}