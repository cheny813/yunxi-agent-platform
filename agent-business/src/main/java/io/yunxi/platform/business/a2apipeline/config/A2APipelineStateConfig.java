package io.yunxi.platform.business.a2apipeline.config;

import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * A2APipelineStateConfig A2A流水线状态配置
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Configuration
public class A2APipelineStateConfig {

    /**
     * 流水线状态枚举
     */
    public enum PipelineState {
        PENDING,
        CODE_FIX,
        CODE_REVIEW,
        TEST_EXEC,
        DEPLOY,
        COMPLETED,
        FAILED
    }

    /**
     * 流水线事件枚举
     */
    public enum PipelineEvent {
        FIX_COMPLETE,
        REVIEW_PASSED,
        REVIEW_FAILED,
        TEST_PASSED,
        TEST_FAILED,
        DEPLOY_COMPLETE,
        DEPLOY_FAILED
    }

    /** 状态转换映射表（当前状态 -> 事件 -> 下一状态） */
    private final Map<PipelineState, Map<PipelineEvent, PipelineState>> transitions = new HashMap<>();

    /**
     * 构造方法，初始化状态转换规则
     */
    public A2APipelineStateConfig() {
        // 初始化状态转换
        Map<PipelineEvent, PipelineState> pendingTransitions = new HashMap<>();
        pendingTransitions.put(PipelineEvent.FIX_COMPLETE, PipelineState.CODE_FIX);

        Map<PipelineEvent, PipelineState> codeFixTransitions = new HashMap<>();
        codeFixTransitions.put(PipelineEvent.REVIEW_PASSED, PipelineState.CODE_REVIEW);
        codeFixTransitions.put(PipelineEvent.REVIEW_FAILED, PipelineState.FAILED);

        Map<PipelineEvent, PipelineState> codeReviewTransitions = new HashMap<>();
        codeReviewTransitions.put(PipelineEvent.TEST_PASSED, PipelineState.TEST_EXEC);
        codeReviewTransitions.put(PipelineEvent.TEST_FAILED, PipelineState.FAILED);

        Map<PipelineEvent, PipelineState> testExecTransitions = new HashMap<>();
        testExecTransitions.put(PipelineEvent.DEPLOY_COMPLETE, PipelineState.DEPLOY);
        testExecTransitions.put(PipelineEvent.DEPLOY_FAILED, PipelineState.FAILED);

        Map<PipelineEvent, PipelineState> deployTransitions = new HashMap<>();
        deployTransitions.put(PipelineEvent.DEPLOY_COMPLETE, PipelineState.COMPLETED);

        transitions.put(PipelineState.PENDING, pendingTransitions);
        transitions.put(PipelineState.CODE_FIX, codeFixTransitions);
        transitions.put(PipelineState.CODE_REVIEW, codeReviewTransitions);
        transitions.put(PipelineState.TEST_EXEC, testExecTransitions);
        transitions.put(PipelineState.DEPLOY, deployTransitions);
    }

    /**
     * 根据当前状态和事件获取下一状态
     *
     * @param currentState 当前状态
     * @param event        流水线事件
     * @return 下一状态，无法转换时返回 null
     */
    public PipelineState getNextState(PipelineState currentState, PipelineEvent event) {
        Map<PipelineEvent, PipelineState> stateTransitions = transitions.get(currentState);
        if (stateTransitions == null) {
            return null;
        }
        return stateTransitions.get(event);
    }
}