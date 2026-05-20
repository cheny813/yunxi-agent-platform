package io.yunxi.platform.business.a2apipeline.service;

import io.yunxi.platform.business.a2apipeline.model.*;
import io.yunxi.platform.shared.dto.StageResult;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PipelineStage 门控逻辑单元测试
 */
class PipelineStageGateTest {

    private CodeFixPipelineOrchestrator orchestrator;

    private CodeFixRequest createRequest() {
        return CodeFixRequest.builder()
                .taskId("TASK-001")
                .description("test fix")
                .build();
    }

    // ---- PipelineStage 接口契约 ----

    @Test
    void unimplementedStage_canExecute_returnsFalse() {
        UnimplementedStage stage = new UnimplementedStage("DEPLOY", "CI/CD 未接入");
        assertFalse(stage.canExecute(Map.of()));
    }

    @Test
    void unimplementedStage_execute_throwsUnsupportedOperationException() {
        UnimplementedStage stage = new UnimplementedStage("DEPLOY", "CI/CD 未接入");
        PipelineContext ctx = PipelineContext.create("inst-1", "task-1", createRequest());
        assertThrows(UnsupportedOperationException.class,
                () -> stage.execute(ctx, Map.of()));
    }

    @Test
    void unimplementedStage_validateResult_returnsFalse() {
        UnimplementedStage stage = new UnimplementedStage("DEPLOY", "CI/CD 未接入");
        StageResult result = StageResult.builder()
                .stageName("DEPLOY")
                .status(StageResult.StageStatus.COMPLETED)
                .build();
        assertFalse(stage.validateResult(result));
    }

    @Test
    void unimplementedStage_getName_returnsConfiguredName() {
        UnimplementedStage stage = new UnimplementedStage("DEPLOY", "CI/CD 未接入");
        assertEquals("DEPLOY", stage.getName());
    }

    // ---- Orchestrator 门控行为 ----

    @Test
    void orchestrator_withNoStages_returnsSuccess() {
        orchestrator = new CodeFixPipelineOrchestrator(List.of());
        PipelineResult result = orchestrator.orchestrate(createRequest());
        assertTrue(result.isSuccess());
    }

    @Test
    void orchestrator_skipsStageWhenCannotExecute() {
        PipelineStage alwaysSkip = new PipelineStage() {
            @Override public String getName() { return "SKIP_STAGE"; }
            @Override public boolean canExecute(Map<String, StageResult> prev) { return false; }
            @Override public StageResult execute(PipelineContext ctx, Map<String, StageResult> prev) {
                fail("不应执行被跳过的阶段");
                return null;
            }
            @Override public boolean validateResult(StageResult r) { return true; }
        };

        orchestrator = new CodeFixPipelineOrchestrator(List.of(alwaysSkip));
        PipelineResult result = orchestrator.orchestrate(createRequest());
        // 跳过的阶段不导致失败，流水线继续
        assertTrue(result.isSuccess());
    }

    @Test
    void orchestrator_stopsOnFailedStage() {
        PipelineStage failStage = new PipelineStage() {
            @Override public String getName() { return "FAIL_STAGE"; }
            @Override public boolean canExecute(Map<String, StageResult> prev) { return true; }
            @Override public StageResult execute(PipelineContext ctx, Map<String, StageResult> prev) {
                return StageResult.builder()
                        .stageName("FAIL_STAGE")
                        .status(StageResult.StageStatus.FAILED)
                        .build();
            }
            @Override public boolean validateResult(StageResult r) { return true; }
        };

        PipelineStage neverReached = new PipelineStage() {
            @Override public String getName() { return "NEVER_REACHED"; }
            @Override public boolean canExecute(Map<String, StageResult> prev) { return true; }
            @Override public StageResult execute(PipelineContext ctx, Map<String, StageResult> prev) {
                fail("不应执行失败阶段之后的阶段");
                return null;
            }
            @Override public boolean validateResult(StageResult r) { return true; }
        };

        orchestrator = new CodeFixPipelineOrchestrator(List.of(failStage, neverReached));
        PipelineResult result = orchestrator.orchestrate(createRequest());
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("FAIL_STAGE"));
    }

    @Test
    void orchestrator_invalidResultCausesFailure() {
        PipelineStage invalidResultStage = new PipelineStage() {
            @Override public String getName() { return "INVALID_RESULT"; }
            @Override public boolean canExecute(Map<String, StageResult> prev) { return true; }
            @Override public StageResult execute(PipelineContext ctx, Map<String, StageResult> prev) {
                return StageResult.builder().stageName("WRONG_NAME").build();
            }
            @Override public boolean validateResult(StageResult r) {
                return r != null && "INVALID_RESULT".equals(r.getStageName());
            }
        };

        orchestrator = new CodeFixPipelineOrchestrator(List.of(invalidResultStage));
        PipelineResult result = orchestrator.orchestrate(createRequest());
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("验证失败"));
    }

    @Test
    void orchestrator_stagesExecuteInOrder() {
        List<String> executionOrder = new ArrayList<>();

        PipelineStage stage1 = new PipelineStage() {
            @Override public String getName() { return "STAGE_1"; }
            @Override public boolean canExecute(Map<String, StageResult> prev) { return true; }
            @Override public StageResult execute(PipelineContext ctx, Map<String, StageResult> prev) {
                executionOrder.add("STAGE_1");
                return StageResult.builder().stageName("STAGE_1")
                        .status(StageResult.StageStatus.COMPLETED).build();
            }
            @Override public boolean validateResult(StageResult r) { return true; }
        };

        PipelineStage stage2 = new PipelineStage() {
            @Override public String getName() { return "STAGE_2"; }
            @Override public boolean canExecute(Map<String, StageResult> prev) {
                return prev.containsKey("STAGE_1");
            }
            @Override public StageResult execute(PipelineContext ctx, Map<String, StageResult> prev) {
                executionOrder.add("STAGE_2");
                return StageResult.builder().stageName("STAGE_2")
                        .status(StageResult.StageStatus.COMPLETED).build();
            }
            @Override public boolean validateResult(StageResult r) { return true; }
        };

        orchestrator = new CodeFixPipelineOrchestrator(List.of(stage1, stage2));
        PipelineResult result = orchestrator.orchestrate(createRequest());
        assertTrue(result.isSuccess());
        assertEquals(List.of("STAGE_1", "STAGE_2"), executionOrder);
    }

    @Test
    void orchestrator_stageCanAccessPreviousResults() {
        PipelineStage producer = new PipelineStage() {
            @Override public String getName() { return "PRODUCER"; }
            @Override public boolean canExecute(Map<String, StageResult> prev) { return true; }
            @Override public StageResult execute(PipelineContext ctx, Map<String, StageResult> prev) {
                return StageResult.builder().stageName("PRODUCER")
                        .status(StageResult.StageStatus.COMPLETED)
                        .output(Map.of("key", "value"))
                        .build();
            }
            @Override public boolean validateResult(StageResult r) { return true; }
        };

        boolean[] consumerSawPreviousResult = {false};
        PipelineStage consumer = new PipelineStage() {
            @Override public String getName() { return "CONSUMER"; }
            @Override public boolean canExecute(Map<String, StageResult> prev) {
                return prev.containsKey("PRODUCER");
            }
            @Override public StageResult execute(PipelineContext ctx, Map<String, StageResult> prev) {
                StageResult producerResult = prev.get("PRODUCER");
                consumerSawPreviousResult[0] = producerResult != null
                        && "value".equals(producerResult.getOutput().get("key"));
                return StageResult.builder().stageName("CONSUMER")
                        .status(StageResult.StageStatus.COMPLETED).build();
            }
            @Override public boolean validateResult(StageResult r) { return true; }
        };

        orchestrator = new CodeFixPipelineOrchestrator(List.of(producer, consumer));
        orchestrator.orchestrate(createRequest());
        assertTrue(consumerSawPreviousResult[0], "后续阶段应能访问前序阶段的结果");
    }

    @Test
    void orchestrator_unimplementedStageSkipped_notCrashed() {
        UnimplementedStage deploy = new UnimplementedStage("DEPLOY", "CI/CD 未接入");
        PipelineStage analysis = new PipelineStage() {
            @Override public String getName() { return "CODE_ANALYSIS"; }
            @Override public boolean canExecute(Map<String, StageResult> prev) { return true; }
            @Override public StageResult execute(PipelineContext ctx, Map<String, StageResult> prev) {
                return StageResult.builder().stageName("CODE_ANALYSIS")
                        .status(StageResult.StageStatus.COMPLETED).build();
            }
            @Override public boolean validateResult(StageResult r) { return true; }
        };

        orchestrator = new CodeFixPipelineOrchestrator(List.of(analysis, deploy));
        PipelineResult result = orchestrator.orchestrate(createRequest());
        assertTrue(result.isSuccess());
    }

    // ---- canExecute 门控依赖链 ----

    @Test
    void codeFixService_canExecute_requiresCompletedAnalysis() {
        CodeFixService fixService = new CodeFixService();

        // 无前序结果
        assertFalse(fixService.canExecute(Map.of()));

        // 前序阶段未完成
        assertFalse(fixService.canExecute(Map.of("CODE_ANALYSIS",
                StageResult.builder().stageName("CODE_ANALYSIS")
                        .status(StageResult.StageStatus.FAILED).build())));

        // 前序阶段完成
        assertTrue(fixService.canExecute(Map.of("CODE_ANALYSIS",
                StageResult.builder().stageName("CODE_ANALYSIS")
                        .status(StageResult.StageStatus.COMPLETED).build())));
    }

    @Test
    void codeReviewService_canExecute_requiresCompletedFix() {
        CodeReviewService reviewService = new CodeReviewService();

        assertFalse(reviewService.canExecute(Map.of()));
        assertFalse(reviewService.canExecute(Map.of("CODE_FIX",
                StageResult.builder().stageName("CODE_FIX")
                        .status(StageResult.StageStatus.FAILED).build())));
        assertTrue(reviewService.canExecute(Map.of("CODE_FIX",
                StageResult.builder().stageName("CODE_FIX")
                        .status(StageResult.StageStatus.COMPLETED).build())));
    }

    @Test
    void testExecutionService_canExecute_requiresCompletedReview() {
        TestExecutionService testService = new TestExecutionService();

        assertFalse(testService.canExecute(Map.of()));
        assertFalse(testService.canExecute(Map.of("CODE_REVIEW",
                StageResult.builder().stageName("CODE_REVIEW")
                        .status(StageResult.StageStatus.FAILED).build())));
        assertTrue(testService.canExecute(Map.of("CODE_REVIEW",
                StageResult.builder().stageName("CODE_REVIEW")
                        .status(StageResult.StageStatus.COMPLETED).build())));
    }

    @Test
    void codeAnalysisService_canExecute_alwaysTrue() {
        CodeAnalysisService analysisService = new CodeAnalysisService();
        assertTrue(analysisService.canExecute(Map.of()));
    }
}
