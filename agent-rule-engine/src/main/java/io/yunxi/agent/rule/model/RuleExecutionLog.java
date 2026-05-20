package io.yunxi.agent.rule.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 规则执行日志实体类（MyBatis 映射）
 * 
 * @author yunxi Team
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleExecutionLog {
    
    /**
     * 主键ID
     */
    private Long id;
    
    /**
     * 规则名称
     */
    private String ruleName;
    
    /**
     * Agent ID
     */
    private String agentId;
    
    /**
     * 任务 ID
     */
    private String taskId;
    
    /**
     * 用户 ID
     */
    private String userId;
    
    /**
     * 用户名
     */
    private String username;
    
    /**
     * 是否触发
     */
    private Boolean triggered;
    
    /**
     * 是否通过
     */
    private Boolean passed;
    
    /**
     * 错误信息
     */
    private String errorMessage;
    
    /**
     * 执行时间（毫秒）
     */
    private Long executionTime;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdTime;
}