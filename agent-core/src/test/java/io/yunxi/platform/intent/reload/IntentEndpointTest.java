package io.yunxi.platform.intent.reload;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import io.yunxi.platform.intent.Intent;
import io.yunxi.platform.intent.classify.HybridIntentClassifier;
import io.yunxi.platform.intent.domain.DomainRegistry;
import io.yunxi.platform.intent.domain.DomainRuntime;
import io.yunxi.platform.intent.domain.ReloadResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link IntentEndpoint} actuator 端点单元测试。
 *
 * <p>覆盖：status 各域快照输出；reload 无 domain 走全量 / 有 domain 走单域 /
 * mode=force 透传；suggest-words 在 LLM 通道未装配（mode=rule）时优雅降级、
 * 装配时返回热度建议词。</p>
 */
@DisplayName("IntentEndpoint actuator 端点单元测试")
class IntentEndpointTest {

    private final DomainRegistry registry = mock(DomainRegistry.class);
    private final IntentReloader reloader = mock(IntentReloader.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<HybridIntentClassifier> provider = mock(ObjectProvider.class);

    private IntentEndpoint endpoint() {
        return new IntentEndpoint(registry, reloader, provider);
    }

    @Test
    @DisplayName("status：返回各域快照信息（版本 / 节点数 / 映射数等）")
    void status() {
        DomainRuntime rt = DomainRuntime.empty("base");
        when(registry.domains()).thenReturn(Set.of("base"));
        when(registry.runtime("base")).thenReturn(rt);

        Map<String, Object> status = endpoint().status();

        assertThat(status).containsKey("base");
        @SuppressWarnings("unchecked")
        Map<String, Object> info = (Map<String, Object>) status.get("base");
        assertThat(info).containsEntry("version", 0L)
                .containsEntry("nodes", 0)
                .containsEntry("entities", 0)
                .containsEntry("terms", 0)
                .containsEntry("mappings", 0);
    }

    @Test
    @DisplayName("reload：无 domain 走全量、有 domain 走单域、mode=force 透传")
    void reloadDispatch() {
        when(reloader.reloadAll(false)).thenReturn(List.of(ReloadResult.ok("base", 1L)));
        when(reloader.reload("nutrition", false)).thenReturn(ReloadResult.ok("nutrition", 2L));
        when(reloader.reload("nutrition", true)).thenReturn(ReloadResult.ok("nutrition", 3L));

        IntentEndpoint endpoint = endpoint();

        List<ReloadResult> all = endpoint.reload(null, null);
        assertThat(all).hasSize(1);
        verify(reloader).reloadAll(false);

        List<ReloadResult> one = endpoint.reload("nutrition", null);
        assertThat(one).hasSize(1);
        assertThat(one.get(0).domain()).isEqualTo("nutrition");
        verify(reloader).reload("nutrition", false);

        endpoint.reload("nutrition", "force");
        verify(reloader).reload("nutrition", true);
    }

    @Test
    @DisplayName("suggest-words：mode=rule 未装配 LLM 通道时优雅降级 enabled=false")
    void suggestWordsDegraded() {
        when(provider.getIfAvailable()).thenReturn(null);

        Map<String, Object> out = endpoint().suggestWords("base", 5);

        assertThat(out).containsEntry("enabled", false);
        assertThat((List<?>) out.get("suggestions")).isEmpty();
    }

    @Test
    @DisplayName("suggest-words：hybrid 装配时返回 LLM 热度建议词")
    void suggestWordsEnabled() {
        HybridIntentClassifier hybrid = mock(HybridIntentClassifier.class);
        when(provider.getIfAvailable()).thenReturn(hybrid);
        when(hybrid.hotSuggestions("base", 3)).thenReturn(List.of(
                new Intent("intent-1", "意图一", 0.9, Intent.MATCHED_LLM)));

        Map<String, Object> out = endpoint().suggestWords("base", 3);

        assertThat(out).containsEntry("enabled", true);
        assertThat((List<?>) out.get("suggestions")).hasSize(1);
    }

    @Test
    @DisplayName("suggest-words：默认 topN=10")
    void suggestWordsDefaultTopN() {
        HybridIntentClassifier hybrid = mock(HybridIntentClassifier.class);
        when(provider.getIfAvailable()).thenReturn(hybrid);
        when(hybrid.hotSuggestions(null, 10)).thenReturn(List.of());

        Map<String, Object> out = endpoint().suggestWords(null, null);

        assertThat(out).containsEntry("enabled", true);
    }
}
