package io.yunxi.platform.shared.mapper;

import org.apache.ibatis.annotations.Mapper;

import io.yunxi.platform.shared.entity.ToolConfigEntity;

import java.util.List;

/**
 * 工具配置数据访问层（MyBatis Mapper）
 * <p>
 * 负责对tool_configs表进行CRUD操作
 * 提供工具配置的查询、插入、更新、删除等功能
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Mapper
public interface ToolConfigMapper {

    /**
     * 插入工具配置
     * <p>
     * 使用 AUTO_INCREMENT 生成 id
     * </p>
     *
     * @param entity 工具配置实体
     * @return 影响行数
     */
    int insert(ToolConfigEntity entity);

    /**
     * 更新工具配置
     * <p>
     * 根据 id 更新记录
     * </p>
     *
     * @param entity 工具配置实体，必须包含id字段
     * @return 影响行数
     */
    int update(ToolConfigEntity entity);

    /**
     * 根据 id 删除工具配置
     *
     * @param id 配置 ID（主键）
     * @return 影响行数
     */
    int deleteById(Long id);

    /**
     * 根据 id 查询工具配置
     *
     * @param id 配置 ID（主键）
     * @return 工具配置实体，不存在则返回null
     */
    ToolConfigEntity findById(Long id);

    /**
     * 查找所有工具配置
     * <p>
     * 按创建时间倒序排列
     * </p>
     *
     * @return 所有工具配置列表
     */
    List<ToolConfigEntity> findAll();

    /**
     * 查找所有启用的工具配置
     * <p>
     * 条件：enabled = true
     * 按创建时间倒序排列
     * </p>
     *
     * @return 启用的工具配置列表
     */
    List<ToolConfigEntity> findByEnabledTrue();

    /**
     * 按工具名称查找配置
     * <p>
     * 工具名称唯一，用于获取特定工具的配置
     * </p>
     *
     * @param toolName 工具名称
     * @return 工具配置实体，不存在则返回null
     */
    ToolConfigEntity findByToolName(String toolName);

    /**
     * 检查工具是否存在
     * <p>
     * 用于避免重复插入相同工具名称的配置
     * </p>
     *
     * @param toolName 工具名称
     * @return true-存在，false-不存在
     */
    boolean existsByToolName(String toolName);

    /**
     * 统计工具配置数量
     *
     * @return 工具配置总数
     */
    long count();
}
