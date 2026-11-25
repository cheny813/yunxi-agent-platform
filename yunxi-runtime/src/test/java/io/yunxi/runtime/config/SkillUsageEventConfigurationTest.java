package io.yunxi.runtime.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yunxi.runtime.middleware.SkillUseEventRecorder;
import io.yunxi.runtime.skilluse.JdbcSkillUseEventStore;
import io.yunxi.runtime.skilluse.SkillUseEventStore;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 技能使用事件遥测装配单测：开启态产出存储与中间件 bean；数据源缺失时启动 fail-fast。
 */
class SkillUsageEventConfigurationTest {

    private final SkillUsageEventConfiguration configuration = new SkillUsageEventConfiguration();

    @SuppressWarnings("unchecked")
    private ObjectProvider<DataSource> provider(DataSource ds) {
        ObjectProvider<DataSource> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(ds);
        return provider;
    }

    @Test
    void enabledWithoutDataSourceFailsFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> configuration.skillUseEventStore(provider(null)));
        assertTrue(e.getMessage().contains("yunxi.skill.jdbc.enabled"));
    }

    @Test
    void enabledWithDataSourceProducesStoreAndRecorder() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");

        JdbcSkillUseEventStore store = configuration.skillUseEventStore(provider(ds));
        SkillUseEventRecorder recorder = configuration.skillUseEventRecorder(store);

        assertTrue(store instanceof SkillUseEventStore);
        assertEquals(java.util.Set.of(io.agentscope.core.middleware.MiddlewareBase.ExtensionPoint.ON_ACTING),
                recorder.activePoints());
    }
}
