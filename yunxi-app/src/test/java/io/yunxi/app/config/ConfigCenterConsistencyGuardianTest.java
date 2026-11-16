package io.yunxi.app.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.yunxi.config.InMemoryConfigCenter;
import org.junit.jupiter.api.Test;

/**
 * {@link ConfigCenterConsistencyGuardian} 行为测试：多副本部署却使用进程内配置中心时，
 * Agent 定义目录会在实例间静默分叉——护栏须显式告警，强制时 fail-closed；共享后端与单机部署不受影响。
 */
class ConfigCenterConsistencyGuardianTest {

    private final InMemoryConfigCenter inMemory = new InMemoryConfigCenter();

    @Test
    void standaloneWithInMemoryCenter_isLegalDeployment() {
        ConfigCenterConsistencyGuardian guardian =
                new ConfigCenterConsistencyGuardian(inMemory, "standalone", false);

        assertDoesNotThrow(() -> guardian.run(null));
    }

    @Test
    void clusterWithInMemoryCenter_warnsButDoesNotBlockByDefault() {
        ConfigCenterConsistencyGuardian guardian =
                new ConfigCenterConsistencyGuardian(inMemory, "cluster", false);

        assertDoesNotThrow(() -> guardian.run(null), "默认仅告警：单实例集群模式仍是合法部署");
    }

    @Test
    void clusterWithInMemoryCenter_failClosedWhenEnforced() {
        ConfigCenterConsistencyGuardian guardian =
                new ConfigCenterConsistencyGuardian(inMemory, "cluster", true);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> guardian.run(null));
        assertTrue(error.getMessage().contains("配置中心"), "异常须指明缺失的是共享配置中心：" + error.getMessage());
    }

    @Test
    void clusterWithSharedCenter_neverFailsEvenWhenEnforced() {
        NacosBackedConfigCenter shared = mock(NacosBackedConfigCenter.class);
        when(shared.isSharedAcrossInstances()).thenReturn(true);

        ConfigCenterConsistencyGuardian guardian =
                new ConfigCenterConsistencyGuardian(shared, "cluster", true);

        assertDoesNotThrow(() -> guardian.run(null));
    }

    @Test
    void clusterWithConfiguredButUnreachableNacos_isTreatedAsNotShared() {
        // Nacos 已配置但连接失败会退化为进程内实现：写入不再跨实例可见，与"进程内实现"属同一缺陷。
        ConfigCenterConsistencyGuardian guardian =
                new ConfigCenterConsistencyGuardian(degradedNacosCenter(), "cluster", true);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> guardian.run(null));
        assertTrue(error.getMessage().contains("Nacos"),
                "异常须指明是 Nacos 连接失败导致的降级，而非笼统的配置中心缺失：" + error.getMessage());
    }

    @Test
    void degradedNacosCenter_warnsButDoesNotBlockByDefault() {
        ConfigCenterConsistencyGuardian guardian =
                new ConfigCenterConsistencyGuardian(degradedNacosCenter(), "cluster", false);

        assertDoesNotThrow(() -> guardian.run(null), "默认仅告警");
    }

    /** 生产构造器连不上 Nacos 时的形态：ConfigService 为空、内部退化为内存实现。 */
    private NacosBackedConfigCenter degradedNacosCenter() {
        ConfigCenterNacosProperties props = new ConfigCenterNacosProperties();
        props.setDataId("yunxi.agent-definitions.json");
        props.setGroup("YUNXI_CONFIG_GROUP");
        return new NacosBackedConfigCenter(null, props);
    }
}
