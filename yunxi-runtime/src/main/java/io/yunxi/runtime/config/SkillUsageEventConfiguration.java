package io.yunxi.runtime.config;

import io.yunxi.runtime.middleware.SkillUseEventRecorder;
import io.yunxi.runtime.skilluse.JdbcSkillUseEventStore;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * 技能使用事件遥测装配（启用条件见 {@link MuseUsageEnabledCondition}：默认跟随 yunxi.skill.jdbc.enabled）。
 *
 * <p>开启前提：MUSE 数据面数据源（skillStoreDataSource，由 yunxi.skill.jdbc.enabled 提供）。
 * 显式开启而数据面未启用时启动 fail-fast——遥测开启却无共享存储等于静默丢失统计数据，宁可拒启。
 * 关闭态不注册任何 bean，行为与旧版完全一致（零回归）。
 */
@Configuration
public class SkillUsageEventConfiguration {

    public static final String ENABLED_PROPERTY = MuseUsageEnabledCondition.USAGE_PROPERTY;

    @Bean
    @Conditional(MuseUsageEnabledCondition.class)
    public JdbcSkillUseEventStore skillUseEventStore(
            @Qualifier("skillStoreDataSource") ObjectProvider<DataSource> skillStoreDataSource) {
        DataSource ds = skillStoreDataSource.getIfAvailable();
        if (ds == null) {
            throw new IllegalStateException(
                    "技能使用遥测已启用（yunxi.muse.usage.enabled）但 MUSE 数据面数据源缺失：请启用 yunxi.skill.jdbc.enabled（复用 skillStoreDataSource）");
        }
        return new JdbcSkillUseEventStore(ds);
    }

    @Bean
    @Conditional(MuseUsageEnabledCondition.class)
    public SkillUseEventRecorder skillUseEventRecorder(JdbcSkillUseEventStore skillUseEventStore) {
        return new SkillUseEventRecorder(skillUseEventStore);
    }
}
