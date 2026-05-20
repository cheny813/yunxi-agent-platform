package io.yunxi.agent.rule.core;

import io.yunxi.agent.rule.model.RuleType;

/**
 * 规则执行监听器接口
 * 
 * 提供规则执行过程中的各种事件监听机制
 * 
 * @author yunxi Team
 * @since 1.0.0
 */
public interface RuleExecutionListener {
    
    /**
     * 规则执行前触发
     * 
     * @param type 规则类型
     * @param context 规则上下文
     */
    default void beforeRuleExecution(RuleType type, RuleContext context) {
        // 默认空实现，子类可选重写
    }
    
    /**
     * 规则执行后触发
     * 
     * @param type 规则类型
     * @param context 规则上下文
     * @param executionTime 执行时间(毫秒)
     */
    default void afterRuleExecution(RuleType type, RuleContext context, long executionTime) {
        // 默认空实现，子类可选重写
    }
    
    /**
     * 规则违反时触发
     * 
     * @param type 规则类型
     * @param context 规则上下文
     * @param exception 规则违反异常
     */
    default void onRuleViolation(RuleType type, RuleContext context, 
                                io.yunxi.agent.rule.exception.RuleViolationException exception) {
        // 默认空实现，子类可选重写
    }
    
    /**
     * 规则执行异常时触发
     * 
     * @param type 规则类型
     * @param context 规则上下文
     * @param exception 规则执行异常
     */
    default void onRuleExecutionException(RuleType type, RuleContext context, 
                                         io.yunxi.agent.rule.exception.RuleExecutionException exception) {
        // 默认空实现，子类可选重写
    }
    
    /**
     * 单个规则执行前触发
     * 
     * @param ruleName 规则名称
     * @param type 规则类型
     * @param context 规则上下文
     */
    default void beforeRuleApplied(String ruleName, RuleType type, RuleContext context) {
        // 默认空实现，子类可选重写
    }
    
    /**
     * 单个规则执行后触发
     * 
     * @param ruleName 规则名称
     * @param type 规则类型
     * @param context 规则上下文
     * @param passed 规则是否通过
     * @param executionTime 执行时间(毫秒)
     */
    default void afterRuleApplied(String ruleName, RuleType type, RuleContext context, 
                                 boolean passed, long executionTime) {
        // 默认空实现，子类可选重写
    }
}