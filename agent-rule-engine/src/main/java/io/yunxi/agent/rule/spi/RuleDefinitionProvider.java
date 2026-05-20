package io.yunxi.agent.rule.spi;

import io.yunxi.agent.rule.core.RuleDefinition;
import java.util.List;

/**
 * 规则定义提供者接口
 * 
 * <p>
 * 用于动态注册规则到规则引擎中。
 * 实现该接口的类需要提供规则定义列表、提供者优先级和名称。
 * </p>
 */
public interface RuleDefinitionProvider {
    
    /**
     * 获取规则定义列表
     * 
     * @return 规则定义列表
     */
    List<RuleDefinition> getRuleDefinitions();
    
    /**
     * 提供者优先级
     * 
     * 优先级越高，执行越早。标准优先级范围：
     * - 高优先级：1000-2000
     * - 中优先级：100-999  
     * - 低优先级：1-99
     * 
     * @return 优先级数值
     */
    int getPriority();
    
    /**
     * 提供者名称
     * 
     * 用于诊断和日志记录
     * 
     * @return 提供者名称
     */
    String getProviderName();
}