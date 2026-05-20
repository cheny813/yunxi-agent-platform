package io.yunxi.agent.rule.model;

import lombok.Getter;

/**
 * 规则类型枚举
 * 
 * @author yunxi Team
 * @since 1.0.0
 */
@Getter
public enum RuleType {
    
    /**
     * 前置规则：任务执行前检查
     * 
     * 典型场景：
     * - 权限检查
     * - 参数校验
     * - 资源限制检查
     */
    PRE("pre", "前置规则"),
    
    /**
     * 运行时规则：任务执行中监控
     * 
     * 典型场景：
     * - 超时控制
     * - 资源监控
     * - 异常熔断
     */
    RUNTIME("runtime", "运行时规则"),
    
    /**
     * 后置规则：任务执行后验证
     * 
     * 典型场景：
     * - 结果验证
     * - 日志记录
     * - 审计追踪
     */
    POST("post", "后置规则");
    
    /**
     * 类型编码
     */
    private final String code;
    
    /**
     * 类型描述
     */
    private final String description;
    
    RuleType(String code, String description) {
        this.code = code;
        this.description = description;
    }
    
    /**
     * 根据编码获取枚举
     * 
     * @param code 类型编码
     * @return RuleType 枚举
     */
    public static RuleType fromCode(String code) {
        for (RuleType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown rule type code: " + code);
    }
}