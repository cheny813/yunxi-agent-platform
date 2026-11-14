package io.yunxi.definition;

/**
 * 租户生命周期状态（状态机）。
 *
 * <ul>
 *   <li>{@code ACTIVE}：正常服务（含未定义租户 fail-open 的默认态）；</li>
 *   <li>{@code SUSPENDED}：已停用，拒绝新建会话（已有会话可继续/优雅收敛）；</li>
 *   <li>{@code ARCHIVED}：已退役，拒绝服务（本实例不布建其 Agent，清理任务按前缀扫描）。</li>
 * </ul>
 */
public enum TenantStatus {
    ACTIVE,
    SUSPENDED,
    ARCHIVED
}
