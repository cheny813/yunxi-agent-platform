package io.yunxi.platform.shared.util.database;

import lombok.Data;

/**
 * 表统计信息
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Data
public class TableStatistics {

    /**
     * 表名
     */
    private String tableName;

    /**
     * 总行数
     */
    private Long rowCount = 0L;

    /**
     * 数据大小（字节）
     */
    private Long dataLength = 0L;

    /**
     * 索引大小（字节）
     */
    private Long indexLength = 0L;

    /**
     * 总大小（字节）
     */
    private Long totalLength = 0L;

    /**
     * 平均行长度（字节）
     */
    private Long avgRowLength = 0L;

    /**
     * 自增值
     */
    private Long autoIncrement = 0L;

    /**
     * 创建时间
     */
    private String createTime;

    /**
     * 更新时间
     */
    private String updateTime;

    /**
     * 字符集
     */
    private String collation;

    /**
     * 获取数据大小（格式化）
     */
    public String getFormattedDataLength() {
        return formatBytes(dataLength);
    }

    /**
     * 获取索引大小（格式化）
     */
    public String getFormattedIndexLength() {
        return formatBytes(indexLength);
    }

    /**
     * 获取总大小（格式化）
     */
    public String getFormattedTotalLength() {
        return formatBytes(totalLength);
    }

    /**
     * 格式化字节数
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    /**
     * 判断是否为大表（行数>10万）
     */
    public boolean isLargeTable() {
        return rowCount != null && rowCount > 100000;
    }

    /**
     * 判断是否为超大表（行数>100万）
     */
    public boolean isVeryLargeTable() {
        return rowCount != null && rowCount > 1000000;
    }

    /**
     * 获取索引数据比
     */
    public double getIndexRatio() {
        if (totalLength == null || totalLength == 0) {
            return 0;
        }
        return indexLength * 100.0 / totalLength;
    }

    @Override
    public String toString() {
        return String.format(
                "表统计 [%s]:\n" +
                "  总行数: %,d\n" +
                "  数据大小: %s\n" +
                "  索引大小: %s (%.1f%%)\n" +
                "  总大小: %s\n" +
                "  平均行长度: %d bytes\n" +
                "  自增值: %,d",
                tableName,
                rowCount,
                getFormattedDataLength(),
                getFormattedIndexLength(),
                getIndexRatio(),
                getFormattedTotalLength(),
                avgRowLength,
                autoIncrement
        );
    }
}
