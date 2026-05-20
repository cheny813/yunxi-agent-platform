package io.yunxi.agent.rule.repository;

import io.yunxi.agent.rule.model.RuleExecutionLog;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 规则执行日志仓库接口
 * 
 * 提供规则执行日志的持久化访问能力
 * 
 * @author yunxi Team
 * @since 1.0.0
 */
@Repository
public interface RuleExecutionLogRepository {
    
    /**
     * 根据会话ID查找执行日志
     * 
     * @param sessionId 会话ID
     * @return 执行日志列表
     */
    List<RuleExecutionLog> findBySessionId(String sessionId);
    
    /**
     * 根据会话ID和规则名称查找执行日志
     * 
     * @param sessionId 会话ID
     * @param ruleName 规则名称
     * @return 执行日志列表
     */
    List<RuleExecutionLog> findBySessionIdAndRuleName(String sessionId, String ruleName);
    
    /**
     * 根据执行时间范围查找执行日志
     * 
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 执行日志列表
     */
    List<RuleExecutionLog> findByExecutionTimeBetween(LocalDateTime startTime, LocalDateTime endTime);
    
    /**
     * 根据规则名称查找执行日志
     * 
     * @param ruleName 规则名称
     * @return 执行日志列表
     */
    List<RuleExecutionLog> findByRuleName(String ruleName);
    
    /**
     * 根据是否通过查找执行日志
     * 
     * @param passed 是否通过
     * @return 执行日志列表
     */
    List<RuleExecutionLog> findByPassed(boolean passed);
    
    /**
     * 保存执行日志
     * 
     * @param log 执行日志
     * @return 保存后的日志
     */
    RuleExecutionLog save(RuleExecutionLog log);
    
    /**
     * 查找所有执行日志
     * 
     * @return 执行日志列表
     */
    List<RuleExecutionLog> findAll();
    
    /**
     * 根据ID查找执行日志
     * 
     * @param id 日志ID
     * @return 执行日志Optional
     */
    Optional<RuleExecutionLog> findById(Long id);
    
    /**
     * 删除执行日志
     * 
     * @param log 执行日志对象
     */
    void delete(RuleExecutionLog log);
}