package io.yunxi.platform.shared.util.database;

import lombok.Data;

import java.util.*;

/**
 * 表结构信息
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Data
public class TableSchema {

    /**
     * 表名
     */
    private String tableName;

    /**
     * 表引擎（如InnoDB）
     */
    private String engine;

    /**
     * 字符集
     */
    private String charset;

    /**
     * 排序规则
     */
    private String collation;

    /**
     * 所有字段名（大小写不敏感，统一小写）
     */
    private Set<String> columns = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    /**
     * 字段详细信息
     */
    private Map<String, ColumnInfo> columnDetails = new HashMap<>();

    /**
     * 主键字段列表
     */
    private List<String> primaryKeys = new ArrayList<>();

    /**
     * 唯一索引字段列表
     */
    private List<String> uniqueKeys = new ArrayList<>();

    /**
     * 表注释
     */
    private String comment;

    /**
     * 获取字段信息
     *
     * @param columnName 字段名（大小写不敏感）
     * @return 字段信息，不存在则返回null
     */
    public ColumnInfo getColumn(String columnName) {
        return columnDetails.get(columnName.toLowerCase());
    }

    /**
     * 判断字段是否存在
     *
     * @param columnName 字段名（大小写不敏感）
     * @return 是否存在
     */
    public boolean hasColumn(String columnName) {
        return columns.contains(columnName.toLowerCase());
    }

    /**
     * 判断是否为主键
     *
     * @param columnName 字段名
     * @return 是否为主键
     */
    public boolean isPrimaryKey(String columnName) {
        return primaryKeys.contains(columnName.toLowerCase());
    }

    /**
     * 查找相似的字段名（大小写不敏感）
     *
     * @param target 目标字段名
     * @return 最相似的字段名，编辑距离>3则返回null
     */
    public String findSimilarColumn(String target) {
        String targetLower = target.toLowerCase();
        if (columns.contains(targetLower)) {
            return targetLower;
        }

        String mostSimilar = null;
        int minDistance = Integer.MAX_VALUE;

        for (String column : columns) {
            int distance = calculateLevenshteinDistance(targetLower, column);
            if (distance < minDistance && distance <= 3) {
                minDistance = distance;
                mostSimilar = column;
            }
        }

        return mostSimilar;
    }

    /**
     * 计算Levenshtein编辑距离
     */
    private int calculateLevenshteinDistance(String str1, String str2) {
        int len1 = str1.length();
        int len2 = str2.length();

        int[][] dp = new int[len1 + 1][len2 + 1];

        for (int i = 0; i <= len1; i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= len2; j++) {
            dp[0][j] = j;
        }

        for (int i = 1; i <= len1; i++) {
            for (int j = 1; j <= len2; j++) {
                int cost = str1.charAt(i - 1) == str2.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }

        return dp[len1][len2];
    }

    @Override
    public String toString() {
        return "TableSchema{" +
                "tableName='" + tableName + '\'' +
                ", columns=" + columns.size() +
                ", primaryKeys=" + primaryKeys +
                '}';
    }
}
