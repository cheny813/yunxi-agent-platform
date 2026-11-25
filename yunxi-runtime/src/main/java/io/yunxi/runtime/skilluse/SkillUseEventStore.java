package io.yunxi.runtime.skilluse;

import java.util.Collection;
import java.util.Map;

/**
 * 技能使用事件存储——技能使用统计的单一真相源。
 *
 * <p>实现必须落共享存储（JDBC），禁止本地文件/内存形态：多实例下每实例各自计数会分叉，
 * 聚合结果将失去跨实例一致性。
 */
public interface SkillUseEventStore {

    /** 记录一条使用事件（append-only）。 */
    void record(SkillUseEvent event);

    /**
     * 按技能名批量聚合统计。
     *
     * @return 仅包含有事件记录的技能；无记录技能不出现在结果中
     */
    Map<String, SkillUsageStats> statsFor(Collection<String> skills);
}
