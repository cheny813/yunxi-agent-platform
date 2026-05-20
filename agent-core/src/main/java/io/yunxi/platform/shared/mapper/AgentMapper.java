package io.yunxi.platform.shared.mapper;

import org.apache.ibatis.annotations.Mapper;

import io.yunxi.platform.shared.entity.AgentEntity;

import java.util.List;

/**
 * Agent 数据访问层（MyBatis Mapper）
 * <p>
 * 负责对agents表进行CRUD操作
 * 提供 Agent 的查询、保存、删除等功能
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Mapper
public interface AgentMapper {

    /**
     * 插入或更新Agent
     * <p>
     * 使用 INSERT ... ON DUPLICATE KEY UPDATE 语法实现幂等操作
     * 如果 name 已存在则更新，否则插入新记录
     * </p>
     *
     * @param entity Agent 实体，必须包含name字段
     * @return 影响行数（1=插入，2=更新）
     */
    int save(AgentEntity entity);

    /**
     * 根据 name 删除 Agent
     *
     * @param name Agent 名称（主键）
     * @return 影响行数
     */
    int deleteByName(String name);

    /**
     * 根据 name 查询 Agent
     *
     * @param name Agent 名称（主键）
     * @return Agent 实体，不存在则返回null
     */
    AgentEntity findByName(String name);

    /**
     * 查找所有Agent
     * <p>
     * 按创建时间倒序排列
     * </p>
     *
     * @return 所有Agent列表
     */
    List<AgentEntity> findAll();

    /**
     * 查找所有启用的 Agent
     * <p>
     * 条件：enabled = true
     * 按创建时间倒序排列
     * </p>
     *
     * @return 启用的Agent列表
     */
    List<AgentEntity> findByEnabledTrue();

    /**
     * 按提供商查找启用的Agent
     * <p>
     * 条件：provider = ? AND enabled = true
     * 按创建时间倒序排列
     * </p>
     *
     * @param provider 提供商名称（如：openai、claude、dashscope）
     * @return 该提供商的启用Agent列表
     */
    List<AgentEntity> findByProviderAndEnabledTrue(String provider);

    /**
     * 按模型名称查找Agent
     * <p>
     * 用于检查模型名称是否已被使用
     * </p>
     *
     * @param modelName 模型名称（如：gpt-4、claude-3、qwen-plus）
     * @return Agent 实体，不存在则返回null
     */
    AgentEntity findByModelName(String modelName);

    /**
     * 统计 Agent 数量
     *
     * @return Agent 总数
     */
    long count();
}
