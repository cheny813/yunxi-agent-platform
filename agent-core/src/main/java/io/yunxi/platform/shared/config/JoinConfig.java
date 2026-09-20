package io.yunxi.platform.shared.config;

import lombok.Data;

/**
 * join 节点的汇聚策略。
 */
@Data
public class JoinConfig {

    /** 汇聚模式：all | any | quorum */
    private String mode;

    /** quorum 模式下所需的终态入边数，须 >0 且 ≤ 入度 */
    private Integer quorum;
}
