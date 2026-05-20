package io.yunxi.platform.business.a2apipeline.service;

import io.yunxi.platform.business.a2apipeline.model.*;
import io.yunxi.platform.shared.dto.StageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 测试执行服务
 * <p>
 * Pipeline Stage 4: 验证修复后的代码通过测试。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
public class TestExecutionService implements PipelineStage {

    /**
     * 获取阶段名称
     *
     * @return 阶段名称 "TEST_EXECUTION"
     */
    @Override
    public String getName() {
        return "TEST_EXECUTION";
    }

    /**
     * 判断测试阶段是否可执行，需要代码审查阶段已完成
     *
     * @param previousResults 前序阶段的执行结果
     * @return 代码审查阶段完成时返回 true，否则返回 false
     */
    @Override
    public boolean canExecute(Map<String, StageResult> previousResults) {
        StageResult reviewResult = previousResults.get("CODE_REVIEW");
        return reviewResult != null
                && reviewResult.getStatus() == StageResult.StageStatus.COMPLETED;
    }

    /**
     * 执行测试阶段（接口方法实现），提取前序阶段结果后委托给重载方法
     *
     * @param context         流水线上下文
     * @param previousResults 前序阶段的执行结果
     * @return 阶段执行结果
     */
    @Override
    public StageResult execute(PipelineContext context, Map<String, StageResult> previousResults) {
        StageResult fixResult = previousResults.get("CODE_FIX");
        StageResult reviewResult = previousResults.get("CODE_REVIEW");
        return execute(context, fixResult, reviewResult);
    }

    /**
     * 验证测试执行结果是否有效
     *
     * @param result 阶段执行结果
     * @return 结果非空、阶段名称为 TEST_EXECUTION 且状态不为 null 时返回 true
     */
    @Override
    public boolean validateResult(StageResult result) {
        return result != null
                && "TEST_EXECUTION".equals(result.getStageName())
                && result.getStatus() != null;
    }

    /**
     * 执行测试验证
     *
     * @param context      流水线上下文
     * @param fixResult    修复阶段结果
     * @param reviewResult 审查阶段结果
     * @return 阶段结果（包含测试通过率和报告）
     */
    public StageResult execute(PipelineContext context, StageResult fixResult, StageResult reviewResult) {
        long startTime = System.currentTimeMillis();
        log.info("[TEST_EXECUTION] 开始测试: instanceId={}", context.getInstanceId());

        try {
            CodeFixRequest request = context.getRequest();

            TestScope scope = identifyTestScope(request);

            TestReport report = runTests(scope, context);

            boolean passed = report.passedTests > 0 && report.failedTests == 0;

            long duration = System.currentTimeMillis() - startTime;
            log.info("[TEST_EXECUTION] 测试完成: instanceId={}, passed={}, failed={}, total={}, duration={}ms",
                    context.getInstanceId(), report.passedTests, report.failedTests,
                    report.totalTests, duration);

            List<StageResult.Finding> findings = new ArrayList<>();
            for (String failure : report.failures) {
                findings.add(StageResult.Finding.builder()
                        .title("测试失败")
                        .severity("HIGH")
                        .description(failure)
                        .build());
            }

            return StageResult.builder()
                    .stageName("TEST_EXECUTION")
                    .status(passed ? StageResult.StageStatus.COMPLETED : StageResult.StageStatus.FAILED)
                    .startTimeMs(startTime)
                    .endTimeMs(System.currentTimeMillis())
                    .durationMs(duration)
                    .findings(findings)
                    .output(Map.of(
                            "totalTests", report.totalTests,
                            "passedTests", report.passedTests,
                            "failedTests", report.failedTests,
                            "skippedTests", report.skippedTests,
                            "passRate", report.totalTests > 0
                                    ? String.format("%.1f%%", (double) report.passedTests / report.totalTests * 100)
                                    : "N/A",
                            "passed", passed))
                    .build();

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[TEST_EXECUTION] 测试执行失败: instanceId={}", context.getInstanceId(), e);

            return StageResult.builder()
                    .stageName("TEST_EXECUTION")
                    .status(StageResult.StageStatus.FAILED)
                    .startTimeMs(startTime)
                    .endTimeMs(System.currentTimeMillis())
                    .durationMs(duration)
                    .output(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    /**
     * 根据修复请求识别测试范围，将源文件路径映射为测试文件路径
     *
     * @param request 代码修复请求
     * @return 测试范围（包含测试文件路径列表）
     */
    private TestScope identifyTestScope(CodeFixRequest request) {
        List<String> testFiles = new ArrayList<>();

        if (request.getContext() != null && request.getContext().getRelatedFiles() != null) {
            for (String file : request.getContext().getRelatedFiles()) {
                if (file.contains("/src/main/")) {
                    String testFile = file.replace("/src/main/", "/src/test/")
                            .replace(".java", "Test.java");
                    testFiles.add(testFile);
                }
            }
        }

        return new TestScope(testFiles);
    }

    /**
     * 运行测试（当前未实现，需接入 Maven/Gradle 测试框架）
     *
     * @param scope   测试范围
     * @param context 流水线上下文
     * @return 测试报告
     * @throws UnsupportedOperationException 测试执行未实现时抛出
     */
    private TestReport runTests(TestScope scope, PipelineContext context) {
        if (scope.testFiles.isEmpty()) {
            log.info("[TEST_EXECUTION] 未识别到测试文件，跳过测试执行");
            return new TestReport();
        }

        throw new UnsupportedOperationException(
                "测试执行未实现，请接入 Maven/Gradle 测试框架后重试。"
                        + "可执行命令示例: mvn test -pl <module> -Dtest=<TestClass>");
    }

    /** 测试范围，包含待执行的测试文件路径列表 */
    private record TestScope(List<String> testFiles) {
    }

    /** 测试报告，记录测试执行结果统计 */
    private static class TestReport {
        /** 总测试数 */
        int totalTests;
        /** 通过的测试数 */
        int passedTests;
        /** 失败的测试数 */
        int failedTests;
        /** 跳过的测试数 */
        int skippedTests;
        /** 失败的测试详情列表 */
        List<String> failures = new ArrayList<>();
    }
}
