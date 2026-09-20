package io.yunxi.platform.shared.config;

import lombok.Data;

/**
 * 编排图节点。字段按 type 区分语义：
 * agent 节点需填 agentId；team 节点需填 teamRef；join 节点需填 join；
 * condition 节点需填 condition（CEL）；approval 节点需填 approval；
 * timer 节点需填 timer；signal 节点需填 signalName；subrun 节点需填 definitionRevisionId。
 */
@Data
public class GraphNodeConfig {

    /** 节点 key，必填且同图内唯一 */
    private String key;

    /** 节点类型：agent | team | join | condition | approval | timer | signal | subrun */
    private String type;

    /** agent 类型：被引用 agent 的 UUID */
    private String agentId;

    /** team 类型：被引用团队/子图的 UUID */
    private String teamRef;

    /** join 类型：汇聚策略 */
    private JoinConfig join;

    /** condition 类型：CEL 表达式（长度 ≤4096 且可编译） */
    private String condition;

    /** approval 类型：人工审批配置 */
    private ApprovalConfig approval;

    /** timer 类型：定时触发配置 */
    private TimerConfig timer;

    /** signal 类型：信号名称 */
    private String signalName;

    /** 失败策略：fail_fast | continue | partial_success */
    private String failurePolicy;

    /** 问题模式：inherit | child */
    private String issueMode;

    /** 节点超时（秒） */
    private Integer timeoutSeconds;

    /** 重试配置 */
    private RetryConfig retry;

    /** subrun 类型：被引用子图定义的 revision UUID */
    private String definitionRevisionId;
}
