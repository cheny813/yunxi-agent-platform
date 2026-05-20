package io.yunxi.platform.shared.util.database;

import lombok.Data;

import java.util.*;

/**
 * SQL验证结果
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Data
public class ValidationResult {

    /**
     * 是否验证通过
     */
    private boolean valid = true;

    /**
     * 错误信息列表（必须修复的问题）
     */
    private List<String> errors = new ArrayList<>();

    /**
     * 警告信息列表（可能影响性能的问题）
     */
    private List<String> warnings = new ArrayList<>();

    /**
     * 建议信息列表（优化建议）
     */
    private List<String> suggestions = new ArrayList<>();

    /**
     * SQL语句
     */
    private String sql;

    /**
     * 表名
     */
    private String tableName;

    /**
     * 添加错误
     */
    public void addError(String error) {
        this.valid = false;
        this.errors.add(error);
    }

    /**
     * 添加警告
     */
    public void addWarning(String warning) {
        this.warnings.add(warning);
    }

    /**
     * 添加建议
     */
    public void addSuggestion(String suggestion) {
        this.suggestions.add(suggestion);
    }

    /**
     * 是否有问题（错误+警告+建议）
     */
    public boolean hasIssues() {
        return !errors.isEmpty() || !warnings.isEmpty() || !suggestions.isEmpty();
    }

    /**
     * 生成报告
     */
    public String generateReport() {
        StringBuilder report = new StringBuilder();

        if (sql != null) {
            report.append("SQL: ").append(sql).append("\n");
        }
        if (tableName != null) {
            report.append("表名: ").append(tableName).append("\n");
        }

        if (!errors.isEmpty()) {
            report.append("❌ 错误(").append(errors.size()).append("):\n");
            for (String error : errors) {
                report.append("  - ").append(error).append("\n");
            }
        }

        if (!warnings.isEmpty()) {
            report.append("⚠️  警告(").append(warnings.size()).append("):\n");
            for (String warning : warnings) {
                report.append("  - ").append(warning).append("\n");
            }
        }

        if (!suggestions.isEmpty()) {
            report.append("💡 建议(").append(suggestions.size()).append("):\n");
            for (String suggestion : suggestions) {
                report.append("  - ").append(suggestion).append("\n");
            }
        }

        return report.toString();
    }

    @Override
    public String toString() {
        return "ValidationResult{" +
                "valid=" + valid +
                ", errors=" + errors.size() +
                ", warnings=" + warnings.size() +
                ", suggestions=" + suggestions.size() +
                '}';
    }
}
