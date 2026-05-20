package io.yunxi.platform.gateway.model;

/**
 * 会话重置策略
 */
public enum SessionResetPolicy {

    /** 不自动重置 */
    NONE,

    /** 每日固定时间重置 */
    DAILY,

    /** 空闲超时后重置 */
    IDLE,

    /** 每日 + 空闲（任一条件满足即重置） */
    BOTH
}
