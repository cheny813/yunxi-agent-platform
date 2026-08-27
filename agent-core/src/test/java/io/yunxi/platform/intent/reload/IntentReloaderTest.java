package io.yunxi.platform.intent.reload;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.yunxi.platform.intent.domain.DomainRegistry;
import io.yunxi.platform.intent.domain.DomainRuntime;
import io.yunxi.platform.intent.domain.ReloadResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link IntentReloader} 热更新门面单元测试。
 *
 * <p>覆盖：reload / reloadAll 透传 registry 结果、监听器广播、监听器异常隔离、
 * 失败结果同样审计并广播。registry 自洽校验 / 原子交换逻辑由 DomainRegistry 侧
 * 测试覆盖，此处只验证门面编排。</p>
 */
@DisplayName("IntentReloader 热更新门面单元测试")
class IntentReloaderTest {

    @Test
    @DisplayName("reload：透传 registry 结果并广播监听器")
    void reloadBroadcast() {
        DomainRegistry registry = mock(DomainRegistry.class);
        ReloadResult expected = ReloadResult.ok("nutrition", 2L);
        when(registry.reload("nutrition", false)).thenReturn(expected);
        when(registry.runtime("nutrition")).thenReturn(DomainRuntime.empty("nutrition"));

        IntentReloadListener listener = mock(IntentReloadListener.class);
        IntentReloader reloader = new IntentReloader(registry, List.of(listener));

        ReloadResult result = reloader.reload("nutrition", false);

        assertThat(result).isEqualTo(expected);
        verify(listener).onReload(expected);
    }

    @Test
    @DisplayName("reloadAll：逐域返回并逐个广播")
    void reloadAllBroadcast() {
        DomainRegistry registry = mock(DomainRegistry.class);
        ReloadResult r1 = ReloadResult.ok("base", 1L);
        ReloadResult r2 = ReloadResult.ok("nutrition", 2L);
        when(registry.reloadAll(false)).thenReturn(List.of(r1, r2));

        IntentReloadListener listener = mock(IntentReloadListener.class);
        IntentReloader reloader = new IntentReloader(registry, List.of(listener));

        List<ReloadResult> results = reloader.reloadAll(false);

        assertThat(results).containsExactly(r1, r2);
        verify(listener).onReload(r1);
        verify(listener).onReload(r2);
    }

    @Test
    @DisplayName("监听器异常不影响主流程（隔离）")
    void listenerExceptionIsolated() {
        DomainRegistry registry = mock(DomainRegistry.class);
        ReloadResult ok = ReloadResult.ok("base", 1L);
        when(registry.reload("base", false)).thenReturn(ok);
        when(registry.runtime("base")).thenReturn(DomainRuntime.empty("base"));

        IntentReloadListener throwing = mock(IntentReloadListener.class);
        Mockito.doThrow(new IllegalStateException("boom")).when(throwing).onReload(ok);

        IntentReloader reloader = new IntentReloader(registry, List.of(throwing));

        assertThat(reloader.reload("base", false)).isEqualTo(ok);
    }

    @Test
    @DisplayName("reload 失败结果同样审计并广播")
    void reloadFailureBroadcast() {
        DomainRegistry registry = mock(DomainRegistry.class);
        ReloadResult failed = ReloadResult.fail("unknown", 0L, "unknown domain: unknown");
        when(registry.reload("unknown", false)).thenReturn(failed);

        IntentReloadListener listener = mock(IntentReloadListener.class);
        IntentReloader reloader = new IntentReloader(registry, List.of(listener));

        ReloadResult result = reloader.reload("unknown", false);

        assertThat(result.success()).isFalse();
        assertThat(result.reason()).contains("unknown domain");
        verify(listener).onReload(failed);
    }
}
