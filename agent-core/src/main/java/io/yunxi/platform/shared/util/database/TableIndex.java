package io.yunxi.platform.shared.util.database;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/**
 * 表索引信息
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Data
public class TableIndex {

    /**
     * 索引名
     */
    private String name;

    /**
     * 索引类型（PRIMARY, UNIQUE, INDEX, FULLTEXT）
     */
    private String type;

    /**
     * 索引字段列表
     */
    private List<IndexColumn> columns = new java.util.ArrayList<>();

    /**
     * 是否唯一
     */
    private boolean unique;

    /**
     * 索引注释
     */
    private String comment;

    /**
     * 索引基数（Cardinality，索引的唯一值数量估计）
     */
    private Long cardinality;

    /**
     * 是否聚集索引
     */
    private boolean clustered;

    @Data
    public static class IndexColumn {
        /**
         * 字段名
         */
        private String name;

        /**
         * 长度（如前缀索引）
         */
        private Integer length;

        /**
         * 排序方式（ASC, DESC）
         */
        private String order;

        public IndexColumn(String name) {
            this.name = name;
            this.order = "ASC";
        }

        public IndexColumn(String name, Integer length) {
            this.name = name;
            this.length = length;
            this.order = "ASC";
        }

        public IndexColumn(String name, Integer length, String order) {
            this.name = name;
            this.length = length;
            this.order = order;
        }
    }
}
