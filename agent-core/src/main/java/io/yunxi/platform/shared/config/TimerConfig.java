package io.yunxi.platform.shared.config;

import lombok.Data;

/**
 * timer 节点的定时触发配置：at 与 durationSeconds 至少其一。
 */
@Data
public class TimerConfig {

    /** 绝对触发时间（RFC3339 或 cron 表达式） */
    private String at;

    /** 相对延迟秒数，须为正数 */
    private Integer durationSeconds;
}
