package io.yunxi.platform.business.rule;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 规则执行日志数据访问层
 *
 * <p>迁移说明：从 agent-rule-engine 迁移至 agent-business。</p>
 */
@Mapper
public interface RuleExecutionLogRepository {

    /**
     * 保存规则执行日志
     *
     * @param log 日志实体
     * @return 影响行数
     */
    @Insert("INSERT INTO rule_execution_log (rule_name, agent_id, task_id, user_id, username, " +
            "triggered, passed, error_message, execution_time, created_time) " +
            "VALUES (#{ruleName}, #{agentId}, #{taskId}, #{userId}, #{username}, " +
            "#{triggered}, #{passed}, #{errorMessage}, #{executionTime}, #{createdTime})")
    int save(RuleExecutionLog log);

    /**
     * 根据任务 ID 查询日志
     *
     * @param taskId 任务 ID
     * @return 日志列表
     */
    @Select("SELECT * FROM rule_execution_log WHERE task_id = #{taskId} ORDER BY created_time DESC")
    List<RuleExecutionLog> findByTaskId(@Param("taskId") String taskId);

    /**
     * 根据用户 ID 查询日志
     *
     * @param userId 用户 ID
     * @return 日志列表
     */
    @Select("SELECT * FROM rule_execution_log WHERE user_id = #{userId} ORDER BY created_time DESC LIMIT 100")
    List<RuleExecutionLog> findByUserId(@Param("userId") String userId);
}
