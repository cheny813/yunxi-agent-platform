package io.yunxi.platform.agent.text2sql.voting;

import io.yunxi.platform.spi.text2sql.DatabaseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * SQL Voter
 * <p>
 * 实现投票机制，通过执行多个候选 SQL 并比较结果集，选择最优 SQL
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Component
public class SqlVoter {

    private static final Logger log = LoggerFactory.getLogger(SqlVoter.class);

    @Autowired(required = false)
    private DatabaseClient databaseClient;

    public DatabaseClient getDatabaseClient() {
        return databaseClient;
    }

    /**
     * 投票接口
     */
    public interface SqlExecutor {
        ExecutionResult execute(String sql);
    }

    /**
     * 执行结果
     */
    public static class ExecutionResult {
        private List<String> columns;
        private List<List<Object>> rows;
        private int rowCount;
        private long executionTime;
        private boolean success;
        private String errorMessage;

        public ExecutionResult() {
        }

        public List<String> getColumns() {
            return columns;
        }

        public void setColumns(List<String> columns) {
            this.columns = columns;
        }

        public List<List<Object>> getRows() {
            return rows;
        }

        public void setRows(List<List<Object>> rows) {
            this.rows = rows;
        }

        public int getRowCount() {
            return rowCount;
        }

        public void setRowCount(int rowCount) {
            this.rowCount = rowCount;
        }

        public long getExecutionTime() {
            return executionTime;
        }

        public void setExecutionTime(long executionTime) {
            this.executionTime = executionTime;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }
    }

    /**
     * 投票主方法
     *
     * @param candidates 候选 SQL 列表
     * @param executor   SQL 执行器
     * @return 最终选中的 SQL
     */
    public String vote(List<String> candidates, SqlExecutor executor) {
        if (candidates == null || candidates.isEmpty()) {
            log.warn("候选 SQL 列表为空");
            return null;
        }

        Map<String, List<Object>> results = new HashMap<>();
        for (String sql : candidates) {
            try {
                ExecutionResult result = executor.execute(sql);
                if (result.isSuccess()) {
                    List<Object> resultKey = new ArrayList<>();
                    resultKey.addAll(result.getRows());
                    results.put(sql, resultKey);
                }
            } catch (Exception e) {
                log.warn("执行 SQL 失败: {}", sql, e);
            }
        }

        // 如果没有执行成功的，返回第一个
        if (results.isEmpty()) {
            return candidates.get(0);
        }

        // 按结果集分组
        Map<String, List<String>> clusters = clusterByResults(results);

        // 找到最大簇
        String bestSql = findLargestCluster(clusters);

        if (bestSql == null) {
            log.warn("未能找到最佳 SQL，使用第一个候选");
            return candidates.get(0);
        }

        log.info("投票完成: candidates={}, selected={}", candidates.size(), bestSql);
        return bestSql;
    }

    /**
     * 按结果集聚类
     *
     * @param results SQL 执行结果映射
     * @return 分组后的簇映射 (结果集 key -> SQL 列表)
     */
    private Map<String, List<String>> clusterByResults(Map<String, List<Object>> results) {
        Map<String, List<String>> clusters = new HashMap<>();

        for (Map.Entry<String, List<Object>> entry : results.entrySet()) {
            String sql = entry.getKey();
            String resultKey = stringifyResult(entry.getValue());

            if (clusters.containsKey(resultKey)) {
                clusters.get(resultKey).add(sql);
            } else {
                List<String> sqlList = new ArrayList<>();
                sqlList.add(sql);
                clusters.put(resultKey, sqlList);
            }
        }

        return clusters;
    }

    /**
     * 找到最大簇
     *
     * @param clusters 分组后的簇
     * @return 最大簇中的 SQL
     */
    private String findLargestCluster(Map<String, List<String>> clusters) {
        String maxKey = null;
        int maxSize = 0;

        for (Map.Entry<String, List<String>> entry : clusters.entrySet()) {
            int size = entry.getValue().size();
            if (size > maxSize) {
                maxSize = size;
                maxKey = entry.getKey();
            }
        }

        if (maxKey != null && clusters.containsKey(maxKey) && !clusters.get(maxKey).isEmpty()) {
            return clusters.get(maxKey).get(0);
        }

        return null;
    }

    /**
     * 将结果集序列化为字符串（用于比较）
     *
     * @param rows 数据行
     * @return 序列化后的字符串
     */
    private String stringifyResult(List<Object> rows) {
        if (rows == null || rows.isEmpty()) {
            return "[]";
        }

        // 简化比较：只比较行数和前 5 行的数据
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(rows.size()).append(":");

        for (int i = 0; i < Math.min(5, rows.size()); i++) {
            sb.append(",");
            sb.append(rows.get(i) != null ? rows.get(i).toString() : "null");
        }

        sb.append("]");
        return sb.toString();
    }

    /**
     * MCP SQL 执行器
     */
    public static class McpSqlExecutor implements SqlExecutor {
        private final DatabaseClient databaseClient;

        public McpSqlExecutor(String databaseId, DatabaseClient databaseClient, int timeout) {
            this.databaseClient = databaseClient;
        }

        @Override
        public ExecutionResult execute(String sql) {
            ExecutionResult result = new ExecutionResult();
            long startTime = System.currentTimeMillis();

            try {
                DatabaseClient.QueryResult queryResult = databaseClient.query(sql);

                if (queryResult == null) {
                    result.setSuccess(false);
                    result.setErrorMessage("查询结果为空");
                    return result;
                }

                if (!queryResult.isSuccess()) {
                    result.setSuccess(false);
                    result.setErrorMessage(queryResult.getErrorMessage());
                    return result;
                }

                // 解析结果
                List<String> columns = new ArrayList<>();
                List<List<Object>> rows = new ArrayList<>();

                try {
                    // 尝试解析 JSON 内容
                    String content = queryResult.getContent();
                    if (content != null && !content.isEmpty()) {
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        com.fasterxml.jackson.databind.JsonNode jsonNode = mapper.readTree(content);

                        if (jsonNode.has("data")) {
                            com.fasterxml.jackson.databind.JsonNode dataNode = jsonNode.get("data");

                            if (dataNode.has("columns")) {
                                dataNode.get("columns").forEach(node -> columns.add(node.asText()));
                            }

                            if (dataNode.has("rows")) {
                                dataNode.get("rows").forEach(rowNode -> {
                                    List<Object> row = new ArrayList<>();
                                    rowNode.forEach(cellNode -> {
                                        if (cellNode.isTextual()) {
                                            row.add(cellNode.asText());
                                        } else if (cellNode.isNumber()) {
                                            row.add(cellNode.asLong());
                                        } else if (cellNode.isBoolean()) {
                                            row.add(cellNode.asBoolean());
                                        } else {
                                            row.add(cellNode.toString());
                                        }
                                    });
                                    rows.add(row);
                                });
                            }
                        }
                    }

                    result.setColumns(columns);
                    result.setRows(rows);
                    result.setRowCount(rows.size());
                    result.setSuccess(true);

                } catch (Exception e) {
                    result.setSuccess(false);
                    result.setErrorMessage("解析查询结果失败: " + e.getMessage());
                }

                result.setExecutionTime(System.currentTimeMillis() - startTime);

            } catch (Exception e) {
                result.setSuccess(false);
                result.setExecutionTime(System.currentTimeMillis() - startTime);
                result.setErrorMessage("执行 SQL 失败: " + e.getMessage());
            }

            return result;
        }
    }
}
