package io.yunxi.runtime.skilluse;

import java.time.Instant;

/**
 * 一次技能使用事件（append-only，不更新历史）。
 *
 * @param skill      技能名（与模型提交的技能清单同口径的字符串）
 * @param tenantId   租户 id（多实例共享存储上的租户维度，可为 null）
 * @param userId     触发使用的用户 id（可为 null——匿名/系统调用）
 * @param eventType  事件类型
 * @param occurredAt 事件时间（null 时由存储端取当前时间）
 */
public record SkillUseEvent(String skill, String tenantId, String userId, SkillUseEventType eventType, Instant occurredAt) {
    public SkillUseEvent {
        if (skill == null || skill.isBlank()) {
            throw new IllegalArgumentException("skill must not be blank");
        }
        if (eventType == null) {
            throw new IllegalArgumentException("eventType must not be null");
        }
    }
}
