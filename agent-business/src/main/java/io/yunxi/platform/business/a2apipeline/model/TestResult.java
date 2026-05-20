package io.yunxi.platform.business.a2apipeline.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * TestResult 测试结果
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
public class TestResult {

    /**
     * 是否所有测试通过
     */
    private boolean allPassed;

    /**
     * 总测试数
     */
    private int totalTests;

    /**
     * 通过的测试数
     */
    private int passedTests;

    /**
     * 失败的测试数
     */
    private int failedTests;

    /**
     * 跳过的测试数
     */
    private int skippedTests;

    /**
     * 测试覆盖率
     */
    private double coveragePercent;

    /**
     * 失败的测试详情
     */
    private List<String> failedTestNames;

    /**
     * 输出数据
     */
    private Map<String, Object> output;

    /**
     * 创建成功结果
     *
     * @param totalTests  总测试数
     * @param passedTests 通过的测试数
     * @return 成功的测试结果实例
     */
    public static TestResult success(int totalTests, int passedTests) {
        return TestResult.builder()
                .allPassed(true)
                .totalTests(totalTests)
                .passedTests(passedTests)
                .failedTests(0)
                .skippedTests(0)
                .coveragePercent(100.0)
                .build();
    }

    /**
     * 创建失败结果
     *
     * @param failedTests 失败的测试数
     * @return 失败的测试结果实例
     */
    public static TestResult failure(int failedTests) {
        return TestResult.builder()
                .allPassed(false)
                .totalTests(0)
                .passedTests(0)
                .failedTests(failedTests)
                .build();
    }
}