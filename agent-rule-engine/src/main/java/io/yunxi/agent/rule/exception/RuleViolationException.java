package io.yunxi.agent.rule.exception;

/**
 * 规则违反异常
 * 
 * 当规则检查不通过时抛出此异常
 * 
 * @author yunxi Team
 * @since 1.0.0
 */
public class RuleViolationException extends RuntimeException {
    
    /**
     * 规则名称
     */
    private final String ruleName;
    
    public RuleViolationException(String message) {
        super(message);
        this.ruleName = null;
    }
    
    public RuleViolationException(String ruleName, String message) {
        super(message);
        this.ruleName = ruleName;
    }
    
    public RuleViolationException(String ruleName, String message, Throwable cause) {
        super(message, cause);
        this.ruleName = ruleName;
    }
    
    public String getRuleName() {
        return ruleName;
    }
}