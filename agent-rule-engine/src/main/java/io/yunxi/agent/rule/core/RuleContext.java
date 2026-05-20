package io.yunxi.agent.rule.core;

import io.yunxi.agent.rule.model.RuleType;
import lombok.Builder;
import lombok.Data;
import org.jeasy.rules.api.Facts;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则执行上下文
 * 
 * 封装规则执行过程中需要的所有信息，包括用户信息、任务信息、执行参数等
 * 
 * @author yunxi Team
 * @since 1.0.0
 */
@Data
@Builder
public class RuleContext {
    
    /**
     * Agent ID
     */
    private String agentId;
    
    /**
     * 用户信息
     */
    private UserInfo userInfo;
    
    /**
     * 任务信息
     */
    private TaskInfo taskInfo;
    
    /**
     * 规则类型
     */
    private RuleType ruleType;
    
    /**
     * 执行参数
     */
    private Map<String, Object> params;
    
    /**
     * 执行结果
     */
    private Object result;
    
    /**
     * 错误信息
     */
    private String errorMessage;
    
    /**
     * 扩展属性（用于规则间传递数据）
     */
    private Map<String, Object> attributes;
    
    /**
     * 转换为 EasyRules Facts
     * 
     * @return Facts 对象
     */
    public Facts toFacts() {
        Facts facts = new Facts();
        facts.put("context", this);
        facts.put("agentId", agentId);
        facts.put("userInfo", userInfo);
        facts.put("taskInfo", taskInfo);
        facts.put("ruleType", ruleType);
        facts.put("params", params != null ? params : new HashMap<>());
        facts.put("result", result);
        facts.put("errorMessage", errorMessage);
        facts.put("attributes", attributes != null ? attributes : new HashMap<>());
        return facts;
    }
    
    /**
     * 从 Facts 创建 RuleContext
     * 
     * @param facts Facts 对象
     * @return RuleContext 对象
     */
    public static RuleContext fromFacts(Facts facts) {
        return facts.get("context");
    }
    
    /**
     * 设置扩展属性
     * 
     * @param key 属性名
     * @param value 属性值
     */
    public void setAttribute(String key, Object value) {
        if (attributes == null) {
            attributes = new HashMap<>();
        }
        attributes.put(key, value);
    }
    
    /**
     * 获取扩展属性
     * 
     * @param key 属性名
     * @return 属性值
     */
    public Object getAttribute(String key) {
        return attributes != null ? attributes.get(key) : null;
    }
    
    // ==================== 内部数据类 ====================
    
    /**
     * 用户信息
     */
    @Data
    @Builder
    public static class UserInfo {
        /**
         * 用户 ID
         */
        private String userId;
        
        /**
         * 用户名
         */
        private String username;
        
        /**
         * 角色列表
         */
        private List<String> roles;
        
        /**
         * 权限列表
         */
        private List<String> permissions;
        
        /**
         * 用户元数据
         */
        private Map<String, Object> metadata;
    }
    
    /**
     * 任务信息
     */
    @Data
    @Builder
    public static class TaskInfo {
        /**
         * 任务 ID
         */
        private String taskId;
        
        /**
         * 任务类型
         */
        private String taskType;
        
        /**
         * 技能名称
         */
        private String skillName;
        
        /**
         * 任务输入参数
         */
        private Map<String, Object> inputs;
        
        /**
         * 任务启动时间（毫秒）
         */
        private Long startTime;
        
        /**
         * 任务超时时间（秒）
         * - null 或 <= 0：使用全局默认超时配置
         * - > 0：使用指定的超时时间
         */
        private Long timeout;
        
        /**
         * 任务元数据
         */
        private Map<String, Object> metadata;
    }
}