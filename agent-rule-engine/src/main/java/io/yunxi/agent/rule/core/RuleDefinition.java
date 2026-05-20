package io.yunxi.agent.rule.core;

import io.yunxi.agent.rule.model.RulePriority;
import io.yunxi.agent.rule.model.RuleType;
import org.jeasy.rules.api.Rule;
import org.jeasy.rules.core.RuleBuilder;

/**
 * 规则定义接口
 * 
 * 所有规则都必须实现此接口，支持转换为 EasyRules 的 Rule 对象
 * 
 * @author yunxi Team
 * @since 1.0.0
 */
public interface RuleDefinition {
    
    /**
     * 获取规则名称（唯一标识）
     * 
     * @return 规则名称
     */
    String getName();
    
    /**
     * 获取规则描述
     * 
     * @return 规则描述
     */
    String getDescription();
    
    /**
     * 获取规则类型
     * 
     * @return 规则类型
     */
    RuleType getType();
    
    /**
     * 获取规则优先级（数字越大优先级越高）
     * 
     * @return 优先级（默认 50）
     */
    default int getPriority() {
        return RulePriority.NORMAL.getValue();
    }
    
    /**
     * 是否启用
     * 
     * @return true 表示启用，false 表示禁用
     */
    default boolean isEnabled() {
        return true;
    }
    
    /**
     * 设置启用状态
     * 
     * <p>注意：这是一个可选方法，默认抛出异常。
     * 如果规则支持动态启用/禁用，应该覆盖此方法。</p>
     * 
     * @param enabled true 表示启用，false 表示禁用
     * @throws UnsupportedOperationException 如果规则不支持动态修改启用状态
     */
    default void setEnabled(boolean enabled) {
        throw new UnsupportedOperationException(
            "规则 [" + getName() + "] 不支持动态启用/禁用，请通过配置文件或数据库修改"
        );
    }
    
    /**
     * 转换为 EasyRules Rule 对象
     * 
     * @return EasyRules Rule 对象
     */
    default Rule toEasyRule() {
        return new RuleBuilder()
            .name(getName())
            .description(getDescription())
            .priority(getPriority())
            .when(facts -> evaluate(facts))
            .then(facts -> execute(facts))
            .build();
    }
    
    /**
     * 规则条件判断
     * 
     * @param facts 规则事实
     * @return true 表示条件成立，触发规则；false 表示条件不成立，不触发规则
     */
    boolean evaluate(org.jeasy.rules.api.Facts facts);
    
    /**
     * 规则执行动作
     * 
     * @param facts 规则事实
     */
    void execute(org.jeasy.rules.api.Facts facts);
}