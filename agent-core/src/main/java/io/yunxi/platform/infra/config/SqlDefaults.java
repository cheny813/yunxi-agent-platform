package io.yunxi.platform.infra.config;

/**
 * SQL脚本默认值提供者
 *
 * <p>
 * 提供PostgreSQL记忆存储的默认SQL脚本
 * 支持占位符{table}替换为实际表名
 * </p>
 *
 * @author yunxi-agent-platform
 */
public final class SqlDefaults {

    private SqlDefaults() {
        // 私有构造函数，防止实例化
    }

    /**
     * 占位符：表名
     */
    public static final String TABLE_PLACEHOLDER = "{table}";

    /**
     * 替换SQL中的表名占位符
     *
     * @param sql       SQL语句
     * @param tableName 表名
     * @return 替换后的SQL语句
     */
    public static String resolveTable(String sql, String tableName) {
        if (sql == null) {
            return null;
        }
        return sql.replace(TABLE_PLACEHOLDER, tableName);
    }

    /**
     * 获取默认建表SQL
     *
     * @param tableName 表名
     * @return 建表SQL语句
     */
    public static String getCreateTableSql(String tableName) {
        return String.format("""
                CREATE TABLE IF NOT EXISTS %s (
                    id SERIAL PRIMARY KEY,
                    conversation_id VARCHAR(100) NOT NULL,
                    role VARCHAR(20) NOT NULL,
                    content TEXT,
                    metadata JSONB,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_conversation_id (conversation_id)
                )
                """, tableName);
    }

    /**
     * 获取默认插入SQL
     *
     * @param tableName 表名
     * @return 插入SQL语句
     */
    public static String getInsertSql(String tableName) {
        return String.format("""
                INSERT INTO %s (conversation_id, role, content, metadata, created_at)
                VALUES (?, ?, ?, ?::jsonb, ?)
                """, tableName);
    }

    /**
     * 获取默认查询SQL
     *
     * @param tableName 表名
     * @return 查询SQL语句
     */
    public static String getSelectSql(String tableName) {
        return String.format("""
                SELECT id, conversation_id, role, content, metadata, created_at
                FROM %s
                WHERE conversation_id = ?
                ORDER BY created_at ASC
                """, tableName);
    }

    /**
     * 获取默认删除SQL
     *
     * @param tableName 表名
     * @return 删除SQL语句
     */
    public static String getDeleteSql(String tableName) {
        return String.format("DELETE FROM %s WHERE conversation_id = ?", tableName);
    }

    /**
     * 获取默认清空表SQL
     *
     * @param tableName 表名
     * @return 清空表SQL语句
     */
    public static String getTruncateSql(String tableName) {
        return String.format("TRUNCATE TABLE %s", tableName);
    }

    /**
     * 获取默认统计SQL
     *
     * @param tableName 表名
     * @return 统计SQL语句
     */
    public static String getCountSql(String tableName) {
        return String.format("SELECT COUNT(*) FROM %s WHERE conversation_id = ?", tableName);
    }
}
