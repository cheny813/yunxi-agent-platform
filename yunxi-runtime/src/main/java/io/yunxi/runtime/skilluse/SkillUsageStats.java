package io.yunxi.runtime.skilluse;

import java.time.Instant;

/**
 * 单个技能的聚合使用统计（从事件表实时查询得出，非持久化快照）。
 *
 * @param skill         技能名
 * @param useCount      全部事件计数（VIEW + USE）
 * @param distinctUsers user_id 非空的去重用户数
 * @param lastUsedAt    最近一次使用时间
 */
public record SkillUsageStats(String skill, long useCount, int distinctUsers, Instant lastUsedAt) {
}
