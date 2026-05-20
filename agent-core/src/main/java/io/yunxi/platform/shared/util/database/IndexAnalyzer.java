package io.yunxi.platform.shared.util.database;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 索引分析器 - 分析表索引使用情况
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
public class IndexAnalyzer {

    /**
     * 分析索引
     */
    public static String analyze(String tableName, List<TableIndex> indexes) {
        StringBuilder report = new StringBuilder();

        report.append("索引分析报告: ").append(tableName).append("\n");
        report.append("-".repeat(50)).append("\n\n");

        if (indexes.isEmpty()) {
            report.append("⚠️  该表没有索引！\n");
            report.append("💡 建议：至少应该为PRIMARY KEY字段创建主键索引\n");
            return report.toString();
        }

        report.append(String.format("总索引数: %d\n\n", indexes.size()));

        int primaryCount = 0;
        int uniqueCount = 0;
        int normalCount = 0;

        for (TableIndex index : indexes) {
            switch (index.getType()) {
                case "PRIMARY":
                    primaryCount++;
                    break;
                case "UNIQUE":
                    uniqueCount++;
                    break;
                default:
                    normalCount++;
                    break;
            }
        }

        report.append(String.format("主键索引: %d\n", primaryCount));
        report.append(String.format("唯一索引: %d\n", uniqueCount));
        report.append(String.format("普通索引: %d\n\n", normalCount));

        // 详细索引列表
        report.append("索引详情:\n");
        for (TableIndex index : indexes) {
            report.append(String.format("\n  [%s] %s\n", index.getType(), index.getName()));

            for (TableIndex.IndexColumn column : index.getColumns()) {
                String columnStr = column.getName();
                if (column.getLength() != null) {
                    columnStr += "(" + column.getLength() + ")";
                }
                if (column.getOrder() != null && !"ASC".equals(column.getOrder())) {
                    columnStr += " " + column.getOrder();
                }
                report.append(String.format("    - %s\n", columnStr));
            }

            if (index.getCardinality() != null) {
                report.append(String.format("    基数: %,d\n", index.getCardinality()));
            }
        }

        // 分析建议
        report.append("\n分析建议:\n");
        List<String> suggestions = generateSuggestions(indexes);

        if (suggestions.isEmpty()) {
            report.append("  ✅ 索引配置合理\n");
        } else {
            for (String suggestion : suggestions) {
                report.append("  💡 ").append(suggestion).append("\n");
            }
        }

        return report.toString();
    }

    /**
     * 生成索引优化建议
     */
    private static List<String> suggestions = new ArrayList<>();

    private static List<String> generateSuggestions(List<TableIndex> indexes) {
        List<String> suggestions = new ArrayList<>();

        boolean hasPrimaryKey = false;
        boolean hasCompositeIndex = false;

        for (TableIndex index : indexes) {
            if ("PRIMARY".equals(index.getType())) {
                hasPrimaryKey = true;
            }

            if (index.getColumns().size() > 1) {
                hasCompositeIndex = true;
            }
        }

        if (!hasPrimaryKey) {
            suggestions.add("建议为表添加主键索引，确保每行数据唯一");
        }

        if (hasCompositeIndex) {
            suggestions.add("有复合索引，确保查询遵循最左前缀原则以充分利用索引");
        }

        if (indexes.size() > 10) {
            suggestions.add("索引数量较多（" + indexes.size() + "），过多的索引会影响写入性能，考虑删除冗余索引");
        }

        return suggestions;
    }

    /**
     * 检查索引是否被有效使用
     */
    public static boolean isIndexEffective(TableIndex index, TableStatistics stats) {
        if (index.getCardinality() == null) {
            return false;
        }

        // 如果基数接近总行数，索引效果不好
        if (stats.getRowCount() != null && index.getCardinality() > stats.getRowCount() * 0.9) {
            return false;
        }

        // 如果基数太低，索引效果也不好
        if (index.getCardinality() < 100) {
            return false;
        }

        return true;
    }

    /**
     * 生成CREATE INDEX语句
     */
    public static String generateCreateIndexSql(TableIndex index) {
        StringBuilder sql = new StringBuilder();

        switch (index.getType()) {
            case "PRIMARY":
                // 主键索引不能通过CREATE INDEX创建
                sql.append("ALTER TABLE ");
                break;
            case "UNIQUE":
                sql.append("CREATE UNIQUE INDEX ");
                break;
            default:
                sql.append("CREATE INDEX ");
                break;
        }

        sql.append(index.getName()).append(" ON ");

        if ("PRIMARY".equals(index.getType())) {
            sql.append("ADD PRIMARY KEY (");
        } else {
            sql.append("(");
        }

        for (int i = 0; i < index.getColumns().size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }

            TableIndex.IndexColumn column = index.getColumns().get(i);
            sql.append(column.getName());

            if (column.getLength() != null) {
                sql.append("(").append(column.getLength()).append(")");
            }

            if (column.getOrder() != null) {
                sql.append(" ").append(column.getOrder());
            }
        }

        sql.append(")");

        return sql.toString();
    }
}
