package io.yunxi.runtime.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

/**
 * MUSE 使用遥测启用条件单测：显式 true/false 优先，未设置时跟随 skill.jdbc.enabled。
 */
class MuseUsageEnabledConditionTest {

    private final MuseUsageEnabledCondition condition = new MuseUsageEnabledCondition();

    private boolean matches(Map<String, Object> props) {
        MockEnvironment env = new MockEnvironment();
        props.forEach(env::setProperty);
        return condition.matches(mockConditionContext(env), null);
    }

    private org.springframework.context.annotation.ConditionContext mockConditionContext(MockEnvironment env) {
        org.springframework.context.annotation.ConditionContext ctx =
                Mockito.mock(org.springframework.context.annotation.ConditionContext.class);
        lenient().when(ctx.getEnvironment()).thenReturn(env);
        return ctx;
    }

    @Test
    void explicitTrueWinsEvenWithoutSkillJdbc() {
        assertTrue(matches(Map.of(MuseUsageEnabledCondition.USAGE_PROPERTY, "true")));
    }

    @Test
    void explicitFalseVetoesEvenWithSkillJdbc() {
        assertFalse(matches(Map.of(
                MuseUsageEnabledCondition.USAGE_PROPERTY, "false",
                MuseUsageEnabledCondition.SKILL_JDBC_PROPERTY, "true")));
    }

    @Test
    void unsetFollowsSkillJdbcEnabled() {
        assertFalse(matches(Map.of()));
        assertTrue(matches(Map.of(MuseUsageEnabledCondition.SKILL_JDBC_PROPERTY, "true")));
    }

    @Test
    void unsetWithSkillJdbcDisabledStaysOff() {
        assertFalse(matches(Map.of(MuseUsageEnabledCondition.SKILL_JDBC_PROPERTY, "false")));
    }

    private static Environment environmentOf(Map<String, Object> props) {
        MockEnvironment env = new MockEnvironment();
        props.forEach(env::setProperty);
        return env;
    }
}
