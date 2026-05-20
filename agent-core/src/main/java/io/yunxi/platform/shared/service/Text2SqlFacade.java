package io.yunxi.platform.shared.service;

/**
 * Text-to-SQL 服务接口
 *
 * <p>
 * 定义自然语言转 SQL 的标准接口，供 agent-text2sql 模块实现。
 * MultiDatabaseQueryService 通过此接口可选注入，避免模块间硬依赖。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public interface Text2SqlFacade {

    /**
     * 使用 LLM 生成 SQL（含投票机制）
     *
     * @param databaseId 数据库标识符
     * @param question   自然语言问题
     * @return 生成的 SQL，失败返回 null
     */
    String text2SqlWithVoting(String databaseId, String question);

    /**
     * 检查 Text-to-SQL 服务是否可用
     *
     * @return true 如果服务已启用且可用
     */
    boolean isAvailable();
}
