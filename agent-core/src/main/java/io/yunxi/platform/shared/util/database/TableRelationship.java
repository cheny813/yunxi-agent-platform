package io.yunxi.platform.shared.util.database;

import lombok.Data;

/**
 * 表关联关系
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Data
public class TableRelationship {

    /**
     * 关系类型
     */
    public enum RelationType {
        /**
         * 外键关系
         */
        FOREIGN_KEY,
        /**
         * 一对一
         */
        ONE_TO_ONE,
        /**
         * 一对多
         */
        ONE_TO_MANY,
        /**
         * 多对多（通过中间表）
         */
        MANY_TO_MANY,
        /**
         * 潜在关联（基于字段名推断）
         */
        POTENTIAL
    }

    /**
     * 源表
     */
    private String fromTable;

    /**
     * 源字段
     */
    private String fromColumn;

    /**
     * 目标表
     */
    private String toTable;

    /**
     * 目标字段
     */
    private String toColumn;

    /**
     * 关系类型
     */
    private RelationType type;

    /**
     * 约束名称（外键名）
     */
    private String constraintName;

    /**
     * 级联删除规则
     */
    private String onDelete;

    /**
     * 级联更新规则
     */
    private String onUpdate;

    /**
     * 置信度（0-1，用于推断关系）
     */
    private double confidence = 1.0;

    public TableRelationship(String fromTable, String fromColumn, String toTable, String toColumn, RelationType type) {
        this.fromTable = fromTable;
        this.fromColumn = fromColumn;
        this.toTable = toTable;
        this.toColumn = toColumn;
        this.type = type;
    }

    public String getRelationSymbol() {
        switch (type) {
            case ONE_TO_ONE:
                return "1-1";
            case ONE_TO_MANY:
                return "1-N";
            case MANY_TO_MANY:
                return "N-M";
            case FOREIGN_KEY:
                return "FK";
            case POTENTIAL:
                return "?";
            default:
                return "-";
        }
    }

    public String getRelationshipDescription() {
        switch (type) {
            case ONE_TO_ONE:
                return String.format("%s.%s ⟷ %s.%s (一对一)", fromTable, fromColumn, toTable, toColumn);
            case ONE_TO_MANY:
                return String.format("%s.%s ⟷ %s.%s (一对多)", fromTable, fromColumn, toTable, toColumn);
            case MANY_TO_MANY:
                return String.format("%s.%s ⟷ %s.%s (多对多)", fromTable, fromColumn, toTable, toColumn);
            case FOREIGN_KEY:
                return String.format("%s.%s → %s.%s (外键)", fromTable, fromColumn, toTable, toColumn);
            case POTENTIAL:
                return String.format("%s.%s → %s.%s (推断, 置信度: %.1f%%)",
                        fromTable, fromColumn, toTable, toColumn, confidence * 100);
            default:
                return String.format("%s.%s - %s.%s", fromTable, fromColumn, toTable, toColumn);
        }
    }
}
