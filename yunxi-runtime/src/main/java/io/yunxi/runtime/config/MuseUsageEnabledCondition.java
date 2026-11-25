package io.yunxi.runtime.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * MUSE 技能使用遥测的启用条件——与 MUSE 数据面联动，减少配置面。
 *
 * <p>语义（三态）：</p>
 * <ul>
 *   <li>显式 {@code yunxi.muse.usage.enabled=true} → 启用；</li>
 *   <li>显式 {@code false} → 关闭（显式否决优先）；</li>
 *   <li>未设置 → 跟随 {@code yunxi.skill.jdbc.enabled}（技能仓库启用则遥测自动启用，
 *       复用同一数据源与生命周期，用户只需决策"要不要 JDBC 技能仓库"一个开关）。</li>
 * </ul>
 *
 * <p>联动开启时数据源必然存在（skillStoreDataSource 由 skill.jdbc.enabled 提供）；
 * 显式 true 而数据面未启用时由 {@code SkillUsageEventConfiguration} 启动 fail-fast 拒启。</p>
 */
public class MuseUsageEnabledCondition implements Condition {

    public static final String USAGE_PROPERTY = "yunxi.muse.usage.enabled";
    public static final String SKILL_JDBC_PROPERTY = "yunxi.skill.jdbc.enabled";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String usage = context.getEnvironment().getProperty(USAGE_PROPERTY);
        if (usage != null) {
            return Boolean.parseBoolean(usage);
        }
        return Boolean.parseBoolean(context.getEnvironment().getProperty(SKILL_JDBC_PROPERTY));
    }
}
