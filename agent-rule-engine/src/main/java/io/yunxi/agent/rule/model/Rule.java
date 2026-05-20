package io.yunxi.agent.rule.model;

import io.yunxi.agent.rule.core.RuleDefinition;
import io.yunxi.agent.rule.core.SpELRule;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jeasy.rules.api.Facts;

import java.time.LocalDateTime;

/**
 * 规则实体类（MyBatis 映射）
 * 
 * @author yunxi Team
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Rule implements RuleDefinition {
    
    /**
     * 主键ID
     */
    private Long id;
    
    /**
     * 规则名称（唯一）
     */
    private String name;
    
    /**
     * 规则描述
     */
    private String description;
    
    /**
     * 规则类型
     */
    private RuleType type;
    
    /**
     * 优先级
     */
    private Integer priority;
    
    /**
     * SpEL 条件表达式
     */
    private String conditionExpression;
    
    /**
     * SpEL 动作表达式
     */
    private String actionExpression;
    
    /**
     * Java 规则实现类全限定名
     */
    private String ruleClass;
    
    /**
     * 是否启用
     */
    private Boolean enabled;
    
    /**
     * 创建人
     */
    private String createdBy;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdTime;
    
    /**
     * 更新人
     */
    private String updatedBy;
    
    /**
     * 更新时间
     */
    private LocalDateTime updatedTime;
    
    @Override
    public int getPriority() {
        return priority != null ? priority : RulePriority.NORMAL.getValue();
    }
    
    @Override
    public boolean isEnabled() {
        return enabled != null ? enabled : true;
    }
    
    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    @Override
    public org.jeasy.rules.api.Rule toEasyRule() {
        // 如果有 SpEL 表达式，使用 SpELRule（替代 MVELRule，修复 CVE-2023-50571）
        if (conditionExpression != null && !conditionExpression.isEmpty()) {
            SpELRule.Builder builder = SpELRule.builder()
                .name(name)
                .description(description)
                .priority(getPriority());
            
            builder.when(conditionExpression);
            
            if (actionExpression != null) {
                builder.then(actionExpression);
            }
            
            return builder.build();
        }
        
        // 如果有规则实现类，使用反射加载
        if (ruleClass != null && !ruleClass.isEmpty()) {
            try {
                Class<?> clazz = Class.forName(ruleClass);
                return ((RuleDefinition) clazz.getDeclaredConstructor().newInstance()).toEasyRule();
            } catch (Exception e) {
                throw new RuntimeException("Failed to load rule class: " + ruleClass, e);
            }
        }
        
        throw new IllegalStateException("Rule must have either conditionExpression or ruleClass");
    }
    
    @Override
    public boolean evaluate(Facts facts) {
        // SpELRule 会自动实现此方法
        throw new UnsupportedOperationException("Use toEasyRule() instead");
    }
    
    @Override
    public void execute(Facts facts) {
        // SpELRule 会自动实现此方法
        throw new UnsupportedOperationException("Use toEasyRule() instead");
    }
}