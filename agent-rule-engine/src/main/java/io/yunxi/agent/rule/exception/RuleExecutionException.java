package io.yunxi.agent.rule.exception;

/**
 * 规则执行异常
 * 
 * 当规则执行过程中发生错误时抛出此异常
 * 
 * @author yunxi Team
 * @since 1.0.0
 */
public class RuleExecutionException extends RuntimeException {
    
    /**
     * 规则名称
     */
    private final String ruleName;
    
    public RuleExecutionException(String message) {
        super(message);
        this.ruleName = null;
    }
    
    public RuleExecutionException(String ruleName, String message) {
        super(message);
        this.ruleName = ruleName;
    }
    
    public RuleExecutionException(String ruleName, String message, Throwable cause) {
        super(message, cause);
        this.ruleName = ruleName;
    }
    
    public String getRuleName() {
        return ruleName;
    }
}