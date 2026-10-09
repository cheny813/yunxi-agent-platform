package io.yunxi.platform.agent.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.yunxi.platform.agent.scheduler.SchedulerAutoConfiguration.TriggerKind;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;

/**
 * SchedulerAutoConfiguration 定时 Agent 注册逻辑的单元测试（不依赖真实 Quartz 调度触发）。
 *
 * <p>覆盖：resolveTrigger 的触发方式选择（cron 优先、回落 fixedRate、再回落 fixedDelay、皆无则 NONE）；
 * resolveAgentConfig 的凭据 / 提示词回落（def 缺省时复用 AgentscopeCoreProperties，core 为 null 时不抛）；
 * registerOne 的守卫（空白名、无触发方式直接返回 false，不触发调度）。</p>
 */
@DisplayName("SchedulerAutoConfiguration 定时 Agent 注册逻辑")
class SchedulerAutoConfigurationTest {

    private final AgentscopeCoreProperties core = mock(AgentscopeCoreProperties.class);

    private SchedulerAutoConfiguration scheduler() {
        return new SchedulerAutoConfiguration(core);
    }

    private ScheduledAgentDefinition def() {
        ScheduledAgentDefinition d = new ScheduledAgentDefinition();
        d.setName("daily-report");
        d.setEnabled(true);
        return d;
    }

    // ---- resolveTrigger：触发方式选择 ----

    @Test
    @DisplayName("cron 优先于 fixedRate / fixedDelay")
    void cronTakesPriority() {
        ScheduledAgentDefinition d = def();
        d.setCron("0 0 2 * * ?");
        d.setFixedRateMs(1000L);
        d.setFixedDelayMs(2000L);
        assertThat(scheduler().resolveTrigger(d)).isEqualTo(TriggerKind.CRON);
    }

    @Test
    @DisplayName("无 cron 时回落到 fixedRate")
    void fixedRateFallback() {
        ScheduledAgentDefinition d = def();
        d.setFixedRateMs(5000L);
        assertThat(scheduler().resolveTrigger(d)).isEqualTo(TriggerKind.FIXED_RATE);
    }

    @Test
    @DisplayName("无 cron / fixedRate 时回落到 fixedDelay")
    void fixedDelayFallback() {
        ScheduledAgentDefinition d = def();
        d.setFixedDelayMs(7000L);
        assertThat(scheduler().resolveTrigger(d)).isEqualTo(TriggerKind.FIXED_DELAY);
    }

    @Test
    @DisplayName("无任何触发方式时返回 NONE（buildScheduleConfig 据此返回 null，registerOne 跳过）")
    void noTriggerReturnsNone() {
        assertThat(scheduler().resolveTrigger(def())).isEqualTo(TriggerKind.NONE);
    }

    // ---- resolveAgentConfig：凭据 / 提示词回落 ----

    @Test
    @DisplayName("def 未提供模型名 / apiKey 时回落到 core 配置")
    void modelFallbackToCore() {
        when(core.getModelName()).thenReturn("core-model");
        when(core.getApiKey()).thenReturn("core-key");
        SchedulerAutoConfiguration.ResolvedAgentConfig r = scheduler().resolveAgentConfig(def());
        assertThat(r.modelName()).isEqualTo("core-model");
        assertThat(r.apiKey()).isEqualTo("core-key");
    }

    @Test
    @DisplayName("def 提供的模型名优先于 core 配置")
    void modelDefWins() {
        when(core.getModelName()).thenReturn("core-model");
        ScheduledAgentDefinition d = def();
        d.setModelName("def-model");
        assertThat(scheduler().resolveAgentConfig(d).modelName()).isEqualTo("def-model");
    }

    @Test
    @DisplayName("def 未提供 sysPrompt 时回落到默认周期提示词")
    void sysPromptFallbackToDefault() {
        assertThat(scheduler().resolveAgentConfig(def()).sysPrompt()).startsWith("你是一个按既定节奏");
    }

    @Test
    @DisplayName("core 配置为 null 时不抛异常，apiKey / modelName 为 null")
    void nullCoreSafe() {
        when(core.getApiKey()).thenReturn(null);
        when(core.getModelName()).thenReturn(null);
        SchedulerAutoConfiguration.ResolvedAgentConfig r = scheduler().resolveAgentConfig(def());
        assertThat(r.apiKey()).isNull();
        assertThat(r.modelName()).isNull();
    }

    // ---- registerOne 守卫 ----

    @Test
    @DisplayName("空白 agent 名直接返回 false，不调度")
    void blankNameSkipped() {
        ScheduledAgentDefinition d = def();
        d.setName("  ");
        assertThat(scheduler().registerOne(d)).isFalse();
    }

    @Test
    @DisplayName("无任何触发方式时 registerOne 返回 false（无触发不调用 schedule）")
    void noTriggerSkipped() {
        assertThat(scheduler().registerOne(def())).isFalse();
    }
}
