package io.yunxi.platform.shared.util.database;

import lombok.Data;

/**
 * 字段信息
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Data
public class ColumnInfo {

    /**
     * 字段名
     */
    private String name;

    /**
     * 字段类型（如bigint(20), varchar(64), decimal(11,2)）
     */
    private String type;

    /**
     * 是否允许NULL
     */
    private Boolean nullable;

    /**
     * 默认值
     */
    private String defaultValue;

    /**
     * 注释
     */
    private String comment;

    /**
     * 是否为主键
     */
    private boolean primaryKey;

    /**
     * 是否唯一
     */
    private boolean unique;

    /**
     * 是否自增
     */
    private boolean autoIncrement;

    /**
     * 字符集（针对字符串类型）
     */
    private String charset;

    /**
     * 获取基础类型（不包含长度）
     * 例如：bigint(20) -> bigint, varchar(64) -> varchar
     *
     * @return 基础类型
     */
    public String getBaseType() {
        if (type == null) {
            return null;
        }
        int parenIndex = type.indexOf('(');
        return parenIndex > 0 ? type.substring(0, parenIndex).toLowerCase() : type.toLowerCase();
    }

    /**
     * 获取类型长度
     * 例如：bigint(20) -> 20, varchar(64) -> 64
     *
     * @return 长度，没有长度则返回-1
     */
    public int getTypeLength() {
        if (type == null) {
            return -1;
        }
        int openParen = type.indexOf('(');
        int closeParen = type.indexOf(')');
        if (openParen > 0 && closeParen > openParen) {
            try {
                String lengthStr = type.substring(openParen + 1, closeParen);
                // 处理 decimal(11,2) 这种情况
                if (lengthStr.contains(",")) {
                    lengthStr = lengthStr.split(",")[0];
                }
                return Integer.parseInt(lengthStr);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * 判断是否为字符串类型
     *
     * @return 是否为字符串类型
     */
    public boolean isStringType() {
        String baseType = getBaseType();
        return baseType != null && (
                baseType.equals("varchar") ||
                baseType.equals("char") ||
                baseType.equals("text") ||
                baseType.equals("longtext") ||
                baseType.equals("mediumtext")
        );
    }

    /**
     * 判断是否为数值类型
     *
     * @return 是否为数值类型
     */
    public boolean isNumericType() {
        String baseType = getBaseType();
        return baseType != null && (
                baseType.equals("int") ||
                baseType.equals("bigint") ||
                baseType.equals("smallint") ||
                baseType.equals("tinyint") ||
                baseType.equals("decimal") ||
                baseType.equals("float") ||
                baseType.equals("double")
        );
    }

    /**
     * 判断是否为日期时间类型
     *
     * @return 是否为日期时间类型
     */
    public boolean isDateTimeType() {
        String baseType = getBaseType();
        return baseType != null && (
                baseType.equals("datetime") ||
                baseType.equals("timestamp") ||
                baseType.equals("date") ||
                baseType.equals("time") ||
                baseType.equals("year")
        );
    }
}
