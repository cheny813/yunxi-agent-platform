package io.yunxi.platform.shared.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 编排图边：由 from 节点指向 to 节点，on 指定触发该边的上游终态。
 */
@Data
public class GraphEdgeConfig {

    /** 起点节点 key */
    private String from;

    /** 终点节点 key */
    private String to;

    /** 触发该边的上游终态集合（success/failure/timeout/rejected/canceled/skipped/error）；为空表示默认（success） */
    private List<String> on = new ArrayList<>();

    /** 边的条件表达式（CEL，可选） */
    private String condition;
}
