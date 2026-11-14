package io.yunxi.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.filter.IConfigFilter;
import com.alibaba.nacos.api.config.listener.FuzzyWatchEventWatcher;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import io.yunxi.config.TenantAgentAllocation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Nacos 持久化路径测试：用轻量 FakeConfigService 替身（避开 Windows 下 Mockito inline-mock 环境坑），
 * 验证分配持久化到<b>独立 dataId</b> 且监听热加载。
 */
class NacosBackedConfigCenterAllocationTest {

    /** 最小可用的 Nacos ConfigService 替身，仅 getConfig / addListener / publishConfig 做实。 */
    static final class FakeConfigService implements ConfigService {
        private final Map<String, String> store = new ConcurrentHashMap<>();
        private final Map<String, List<Listener>> listeners = new ConcurrentHashMap<>();

        @Override
        public String getConfig(String dataId, String group, long timeoutMs) {
            return store.get(dataId);
        }

        @Override
        public String getConfigAndSignListener(String dataId, String group, long timeoutMs, Listener listener) {
            return store.get(dataId);
        }

        @Override
        public void addListener(String dataId, String group, Listener listener) {
            listeners.computeIfAbsent(dataId, k -> new CopyOnWriteArrayList<>()).add(listener);
        }

        @Override
        public boolean publishConfig(String dataId, String group, String content) {
            store.put(dataId, content);
            return true;
        }

        @Override
        public boolean publishConfig(String dataId, String group, String content, String type) {
            return publishConfig(dataId, group, content);
        }

        @Override
        public boolean publishConfigCas(String dataId, String group, String content, String casMd5) {
            return publishConfig(dataId, group, content);
        }

        @Override
        public boolean publishConfigCas(String dataId, String group, String content, String casMd5, String type) {
            return publishConfig(dataId, group, content);
        }

        @Override
        public boolean removeConfig(String dataId, String group) {
            store.remove(dataId);
            return true;
        }

        @Override
        public void removeListener(String dataId, String group, Listener listener) {
            List<Listener> l = listeners.get(dataId);
            if (l != null) {
                l.remove(listener);
            }
        }

        @Override
        public String getServerStatus() {
            return "UP";
        }

        @Override
        public void addConfigFilter(IConfigFilter filter) {
        }

        @Override
        public void shutDown() {
        }

        @Override
        public void fuzzyWatch(String dataId, FuzzyWatchEventWatcher watcher) {
        }

        @Override
        public void fuzzyWatch(String dataId, String group, FuzzyWatchEventWatcher watcher) {
        }

        @Override
        public Future<java.util.Set<String>> fuzzyWatchWithGroupKeys(String dataId, FuzzyWatchEventWatcher watcher) {
            return null;
        }

        @Override
        public Future<java.util.Set<String>> fuzzyWatchWithGroupKeys(String dataId, String group,
                FuzzyWatchEventWatcher watcher) {
            return null;
        }

        @Override
        public void cancelFuzzyWatch(String dataId, FuzzyWatchEventWatcher watcher) {
        }

        @Override
        public void cancelFuzzyWatch(String dataId, String group, FuzzyWatchEventWatcher watcher) {
        }

        /** 模拟远端推送：写入并触发该 dataId 的监听器。 */
        void fire(String dataId, String content) {
            store.put(dataId, content);
            List<Listener> l = listeners.get(dataId);
            if (l != null) {
                l.forEach(x -> x.receiveConfigInfo(content));
            }
        }
    }

    private ConfigCenterNacosProperties props() {
        ConfigCenterNacosProperties p = new ConfigCenterNacosProperties();
        p.setEnabled(true);
        return p;
    }

    @Test
    void registerPersistsToAllocationDataId_andGetReadsBack() {
        FakeConfigService fake = new FakeConfigService();
        NacosBackedConfigCenter cc = new NacosBackedConfigCenter(fake, props());
        TenantAgentAllocation alloc = new TenantAgentAllocation(Map.of("t1", Set.of("a1")), Set.of("a9"));
        cc.registerTenantAllocation(alloc);

        // 持久化到独立 allocation dataId（非 Agent 定义 dataId），且内容含 a1
        String raw = fake.getConfig(props().getAllocationDataId(), props().getGroup(), 1000);
        assertNotNull(raw);
        assertTrue(raw.contains("a1"));
        // 读取回写一致
        assertEquals(Set.of("a1"), cc.getTenantAllocation().resolve("t1").orElseThrow());
    }

    @Test
    void hotReload_viaListener_updatesImage() {
        FakeConfigService fake = new FakeConfigService();
        NacosBackedConfigCenter cc = new NacosBackedConfigCenter(fake, props());
        // 模拟远端对 allocation dataId 的变更推送
        fake.fire(props().getAllocationDataId(), "{\"entries\":{\"t2\":[\"b1\"]},\"defaultAgents\":[]}");
        assertEquals(Set.of("b1"), cc.getTenantAllocation().resolve("t2").orElseThrow());
    }

    @Test
    void disabledNacos_constructorDoesNotThrow_andReturnsEmpty() {
        ConfigCenterNacosProperties p = new ConfigCenterNacosProperties();
        p.setEnabled(false);
        // configService == null 降级路径：构造不得抛错
        NacosBackedConfigCenter cc = new NacosBackedConfigCenter((ConfigService) null, p);
        assertTrue(cc.getTenantAllocation().entries().isEmpty());
    }
}
