package io.yunxi.platform.shared.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 多数据库配置
 * <p>
 * 配置各业务数据库的显示名称和 NL2SQL 表名映射。
 * 数据库连接信息由 mcp-database 服务器统一管理（通过 YAML 配置），
 * Agent 平台通过 db_id 参数路由到对应的数据库。
 * </p>
 *
 * 使用示例：
 * 
 * <pre>
 * mcp-databases:
 *   nutrition:
 *     display-name: 营养数据库
 *     table-name-mappings:
 *       菜品: dish
 *       食材: ingredient
 *   finance:
 *     display-name: 经费数据库
 *   foodsafety:
 *     display-name: 食安数据库
 * </pre>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "mcp-databases")
public class MultiDatabaseConfig {

    /**
     * 数据库配置映射
     * key: 数据库标识符（如 nutrition, finance, foodsafety）
     * value: 数据库配置
     */
    private Map<String, DatabaseInfo> databases = new HashMap<>();

    /**
     * 默认数据库标识符
     */
    private String defaultDatabase = "nutrition";

    /**
     * 数据库信息
     *
     * @author yunxi-agent-platform
     */
    @Data
    public static class DatabaseInfo {
        /**
         * 显示名称
         */
        private String displayName;

        /**
         * 表名映射配置
         * key: 自然语言关键词（如 "菜品", "食材", "营养素"）
         * value: 实际表名
         */
        private Map<String, String> tableNameMappings = new HashMap<>();

        /**
         * 操作类型映射
         */
        private Map<String, String> operationMappings = new HashMap<>();

        public DatabaseInfo() {
            // 默认操作类型映射
            operationMappings.put("统计", "statistics");
            operationMappings.put("汇总", "statistics");
            operationMappings.put("总数", "statistics");
            operationMappings.put("数量", "statistics");
            operationMappings.put("个数", "statistics");
            operationMappings.put("分组", "group");
            operationMappings.put("报表", "report");
            operationMappings.put("报告", "report");
        }
    }

    /**
     * 获取默认数据库配置
     *
     * @return 数据库信息
     */
    public DatabaseInfo getDefaultDatabaseInfo() {
        return databases.get(defaultDatabase);
    }

    /**
     * 根据数据库标识符获取配置
     *
     * @param databaseId 数据库标识符
     * @return 数据库信息，如果不存在返回 null
     */
    public DatabaseInfo getDatabaseInfo(String databaseId) {
        return databases.get(databaseId);
    }

    /**
     * 获取所有数据库标识符
     *
     * @return 数据库标识符列表
     */
    public Set<String> getDatabaseIds() {
        return databases.keySet();
    }

    /**
     * 检查数据库是否存在
     *
     * @param databaseId 数据库标识符
     * @return true-存在，false-不存在
     */
    public boolean hasDatabase(String databaseId) {
        return databases.containsKey(databaseId);
    }

    /**
     * 添加数据库配置
     *
     * @param databaseId   数据库标识符
     * @param databaseInfo 数据库信息
     */
    public void addDatabase(String databaseId, DatabaseInfo databaseInfo) {
        databases.put(databaseId, databaseInfo);
    }

    /**
     * 根据自然语言关键词查找表名
     *
     * @param databaseId 数据库标识符
     * @param keyword    自然语言关键词
     * @return 表名，如果未找到返回 null
     */
    public String findTableNameByKeyword(String databaseId, String keyword) {
        DatabaseInfo dbInfo = databases.get(databaseId);
        if (dbInfo == null || dbInfo.getTableNameMappings() == null) {
            return null;
        }

        // 精确匹配
        String tableName = dbInfo.getTableNameMappings().get(keyword);
        if (tableName != null) {
            return tableName;
        }

        // 模糊匹配（检查关键词是否包含在 key 中）
        for (Map.Entry<String, String> entry : dbInfo.getTableNameMappings().entrySet()) {
            if (keyword.contains(entry.getKey()) || entry.getKey().contains(keyword)) {
                return entry.getValue();
            }
        }

        return null;
    }

    /**
     * 添加表名映射
     *
     * @param databaseId 数据库标识符
     * @param keyword    自然语言关键词
     * @param tableName  实际表名
     */
    public void addTableNameMapping(String databaseId, String keyword, String tableName) {
        DatabaseInfo dbInfo = databases.computeIfAbsent(databaseId, k -> new DatabaseInfo());
        if (dbInfo.getTableNameMappings() == null) {
            dbInfo.setTableNameMappings(new HashMap<>());
        }
        dbInfo.getTableNameMappings().put(keyword, tableName);
    }

    /**
     * 获取数据库列表摘要
     *
     * @return 数据库摘要信息
     */
    public Map<String, Object> getSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("defaultDatabase", defaultDatabase);
        summary.put("totalDatabases", databases.size());

        List<Map<String, Object>> dbList = new ArrayList<>();
        for (Map.Entry<String, DatabaseInfo> entry : databases.entrySet()) {
            Map<String, Object> dbInfo = new LinkedHashMap<>();
            dbInfo.put("id", entry.getKey());
            dbInfo.put("displayName", entry.getValue().getDisplayName());
            dbInfo.put("tableCount", entry.getValue().getTableNameMappings() != null
                    ? entry.getValue().getTableNameMappings().size()
                    : 0);
            dbList.add(dbInfo);
        }
        summary.put("databases", dbList);

        return summary;
    }
}
