package io.yunxi.runtime.skilluse;

/**
 * 技能使用事件类型。
 *
 * <p>记录语义为「调用意向」——模型决定在本轮发起该技能的加载/使用时即记录，不等待调用结果；
 * 与框架 SkillUsageMiddleware 的 bump 语义一致。VIEW 对应技能内容加载类工具（load_skill_through_path /
 * read_skill），USE 对应显式技能调用工具（use_skill）。
 */
public enum SkillUseEventType {
    VIEW,
    USE
}
