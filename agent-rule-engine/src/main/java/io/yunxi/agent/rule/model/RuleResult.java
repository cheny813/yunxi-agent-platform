package io.yunxi.agent.rule.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则执行结果
 * 
 * @author yunxi Team
 * @since 1.0.0
 */
@Data
@Builder
public class RuleResult {
    
    /**
     * 是否通过
     */
    private boolean passed;
    
    /**
     * 错误信息
     */
    private String errorMessage;
    
    /**
     * 被触发的规则列表
     */
    private List<String> triggeredRules;
    
    /**
     * 执行耗时（毫秒）
     */
    private Long executionTime;
    
    /**
     * 创建成功结果
     * 
     * @return RuleResult
     */
    public static RuleResult success() {
        return RuleResult.builder()
            .passed(true)
            .triggeredRules(new ArrayList<>())
            .build();
    }
    
    /**
     * 创建失败结果
     * 
     * @param errorMessage 错误信息
     * @return RuleResult
     */
    public static RuleResult failure(String errorMessage) {
        return RuleResult.builder()
            .passed(false)
            .errorMessage(errorMessage)
            .triggeredRules(new ArrayList<>())
            .build();
    }
    
    /**
     * 创建失败结果（带触发的规则）
     * 
     * @param errorMessage 错误信息
     * @param triggeredRules 触发的规则列表
     * @return RuleResult
     */
    public static RuleResult failure(String errorMessage, List<String> triggeredRules) {
        return RuleResult.builder()
            .passed(false)
            .errorMessage(errorMessage)
            .triggeredRules(triggeredRules != null ? triggeredRules : new ArrayList<>())
            .build();
    }
    
    /**
     * 添加触发的规则
     * 
     * @param ruleName 规则名称
     */
    public void addTriggeredRule(String ruleName) {
        if (triggeredRules == null) {
            triggeredRules = new ArrayList<>();
        }
        triggeredRules.add(ruleName);
    }
}