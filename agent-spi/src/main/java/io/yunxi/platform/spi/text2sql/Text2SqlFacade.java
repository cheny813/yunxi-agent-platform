package io.yunxi.platform.spi.text2sql;

/**
 * Text-to-SQL 门面接口
 *
 * <p>为其他模块提供自然语言转 SQL 的统一入口。</p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public interface Text2SqlFacade {

    /**
     * 将自然语言问题转换为 SQL 查询（带投票机制）
     *
     * @param databaseId 数据库标识
     * @param question   自然语言问题
     * @return 生成的 SQL 查询语句
     */
    String text2SqlWithVoting(String databaseId, String question);

    /**
     * 检查 Text2SQL 服务是否可用
     *
     * @return true 如果服务已配置且可用
     */
    boolean isAvailable();
}
