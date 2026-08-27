package io.yunxi.platform.intent.reload;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.yunxi.platform.intent.config.IntentProperties;
import io.yunxi.platform.intent.domain.DomainRegistry;
import io.yunxi.platform.intent.domain.ReloadResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link IntentFilePoller} 文件轮询器单元测试（可选触发源）。
 *
 * <p>覆盖：首次轮询仅记录基准 mtime 不触发；file: 源 mtime 变化后触发该域
 * 热更新；classpath 资源（非 file: 前缀）不参与轮询。</p>
 */
@DisplayName("IntentFilePoller 文件轮询器单元测试")
class IntentFilePollerTest {

    @TempDir
    Path tmpDir;

    @Test
    @DisplayName("file: 源 mtime 变化触发 reload；首次轮询仅记录基准不触发")
    void pollTriggersOnChange() throws Exception {
        Path yml = tmpDir.resolve("intent-tree.yml");
        Files.writeString(yml, "tree: {intents: []}");

        DomainRegistry registry = mock(DomainRegistry.class);
        when(registry.sourceLocations()).thenReturn(Map.of("base", List.of("file:" + yml)));
        when(registry.reload("base", false)).thenReturn(ReloadResult.ok("base", 2L));

        IntentReloader reloader = new IntentReloader(registry, List.of());
        IntentProperties props = new IntentProperties();
        props.getReload().setPollSeconds(1);
        IntentFilePoller poller = new IntentFilePoller(reloader, props);

        // 首次轮询：建立 mtime 基准，不触发
        poller.poll();
        verify(registry, never()).reload(anyString(), anyBoolean());

        // 修改文件 mtime（显式前移，避免文件系统时间粒度抖动）→ 再次轮询触发
        Files.setLastModifiedTime(yml,
                FileTime.fromMillis(Files.getLastModifiedTime(yml).toMillis() + 60_000));
        poller.poll();
        verify(registry, times(1)).reload(eq("base"), eq(false));

        // mtime 不再变化 → 不重复触发
        poller.poll();
        verify(registry, times(1)).reload(eq("base"), eq(false));
    }

    @Test
    @DisplayName("classpath 资源（非 file: 前缀）不参与轮询")
    void classpathIgnored() {
        DomainRegistry registry = mock(DomainRegistry.class);
        when(registry.sourceLocations()).thenReturn(Map.of(
                "base", List.of("classpath:config/intent/intent-tree.yml")));

        IntentReloader reloader = new IntentReloader(registry, List.of());
        IntentProperties props = new IntentProperties();
        props.getReload().setPollSeconds(1);
        IntentFilePoller poller = new IntentFilePoller(reloader, props);

        poller.poll();
        poller.poll();

        verify(registry, never()).reload(anyString(), anyBoolean());
        assertThat(props.getReload().getPollSeconds()).isEqualTo(1);
    }
}
