package io.yunxi.agent.rule.model;

import lombok.Getter;

/**
 * 规则优先级枚举
 * 
 * 优先级数字越大，执行顺序越靠前
 * 
 * @author yunxi Team
 * @since 1.0.0
 */
@Getter
public enum RulePriority {
    
    /**
     * 最低优先级（1）
     * 
     * 典型场景：审计日志、统计记录
     */
    LOWEST(1, "最低优先级"),
    
    /**
     * 低优先级（25）
     * 
     * 典型场景：非关键性检查
     */
    LOW(25, "低优先级"),
    
    /**
     * 普通优先级（50）
     * 
     * 典型场景：一般业务规则
     */
    NORMAL(50, "普通优先级"),
    
    /**
     * 高优先级（75）
     * 
     * 典型场景：重要业务规则、资源检查
     */
    HIGH(75, "高优先级"),
    
    /**
     * 最高优先级（100）
     * 
     * 典型场景：权限检查、安全规则
     */
    HIGHEST(100, "最高优先级");
    
    /**
     * 优先级数值
     */
    private final int value;
    
    /**
     * 优先级描述
     */
    private final String description;
    
    RulePriority(int value, String description) {
        this.value = value;
        this.description = description;
    }
    
    /**
     * 根据数值获取枚举
     * 
     * @param value 优先级数值
     * @return RulePriority 枚举
     */
    public static RulePriority fromValue(int value) {
        for (RulePriority priority : values()) {
            if (priority.value == value) {
                return priority;
            }
        }
        throw new IllegalArgumentException("Unknown rule priority value: " + value);
    }
}