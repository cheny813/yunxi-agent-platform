package io.yunxi.platform.shared.util.database;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL性能分析报告
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Data
public class SqlPerformanceReport {

    /**
     * SQL语句
     */
    private String sql;

    /**
     * 查询类型（SELECT, INSERT, UPDATE, DELETE）
     */
    private String queryType;

    /**
     * 执行计划
     */
    private String explainResult;

    /**
     * 是否使用了索引
     */
    private boolean indexUsed = false;

    /**
     * 使用的索引
     */
    private String indexName;

    /**
     * 扫描行数估计
     */
    private Long rowsExamined;

    /**
     * 估算执行时间（毫秒）
     */
    private Long estimatedTime;

    /**
     * 性能等级（EXCELLENT, GOOD, FAIR, POOR）
     */
    private PerformanceLevel performanceLevel = PerformanceLevel.GOOD;

    /**
     * 优化建议
     */
    private List<String> optimizations = new ArrayList<>();

    /**
     * 警告信息
     */
    private List<String> warnings = new ArrayList<>();

    /**
     * 扫描的表数量
     */
    private Integer tableCount;

    /**
     * 临时表使用情况
     */
    private boolean usingTempTable;

    /**
     * 文件排序使用情况
     */
    private boolean usingFilesort;

    /**
     * 性能等级枚举
     */
    public enum PerformanceLevel {
        EXCELLENT("优秀", "✅"),
        GOOD("良好", "👍"),
        FAIR("一般", "⚠️"),
        POOR("较差", "❌");

        private final String description;
        private final String icon;

        PerformanceLevel(String description, String icon) {
            this.description = description;
            this.icon = icon;
        }

        public String getLabel() {
            return icon + " " + description;
        }
    }

    /**
     * 生成性能报告
     */
    public String generateReport() {
        StringBuilder report = new StringBuilder();

        report.append("SQL性能分析报告\n");
        report.append("=" .repeat(60)).append("\n\n");

        report.append("SQL语句:\n");
        report.append("  ").append(sql).append("\n\n");

        report.append("查询类型: ").append(queryType).append("\n");
        report.append("性能等级: ").append(performanceLevel.getLabel()).append("\n\n");

        report.append("执行计划:\n");
        if (explainResult != null && !explainResult.isEmpty()) {
            report.append(explainResult).append("\n\n");
        } else {
            report.append("  (暂无执行计划)\n\n");
        }

        report.append("关键指标:\n");
        report.append("  使用索引: ").append(indexUsed ? "✅ " + indexName : "❌ 否").append("\n");
        report.append("  扫描行数: ").append(rowsExamined != null ? String.format("%,d", rowsExamined) : "未知").append("\n");
        report.append("  涉及表数: ").append(tableCount != null ? tableCount : "未知").append("\n");
        report.append("  使用临时表: ").append(usingTempTable ? "⚠️ 是" : "否").append("\n");
        report.append("  文件排序: ").append(usingFilesort ? "⚠️ 是" : "否").append("\n\n");

        if (!warnings.isEmpty()) {
            report.append("警告:\n");
            for (String warning : warnings) {
                report.append("  ⚠️  ").append(warning).append("\n");
            }
            report.append("\n");
        }

        if (!optimizations.isEmpty()) {
            report.append("优化建议:\n");
            for (int i = 0; i < optimizations.size(); i++) {
                report.append("  ").append(i + 1).append(". ").append(optimizations.get(i)).append("\n");
            }
            report.append("\n");
        }

        return report.toString();
    }

    /**
     * 添加警告
     */
    public void addWarning(String warning) {
        this.warnings.add(warning);
        // 有警告时降低性能等级
        if (performanceLevel == PerformanceLevel.EXCELLENT) {
            performanceLevel = PerformanceLevel.GOOD;
        }
    }

    /**
     * 添加优化建议
     */
    public void addOptimization(String optimization) {
        this.optimizations.add(optimization);
    }
}
