package io.yunxi.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import io.yunxi.config.TenantAgentAllocation;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 真·Nacos 集成测试：端到端验证「注册分配 → 持久化到独立 allocation dataId → 远端推送变更 → 监听热加载」。
 *
 * <p>与 {@link NacosBackedConfigCenterAllocationTest}（FakeConfigService 替身，无真实 Nacos）互补：本类连真实 Nacos，
 * 验证 SDK 往返与监听回调在真实服务端下的行为。
 *
 * <p>环境约束：需可达的 Nacos（地址取 {@code NACOS_SERVER_ADDR}，默认 {@code 127.0.0.1:8848}；
 * 命名空间取 {@code NACOS_NAMESPACE}，默认 {@code public}）。不可达时整类跳过——符合本仓「Nacos 层靠 CI 真实
 * Nacos 集成验证、in-repo 用替身覆盖逻辑」的既有约定，不会让默认 {@code mvn test} 失败。
 */
class NacosTenantAllocationIntegrationTest {

    private static final String DATA_ID = "yunxi.tenant-agent-allocation.it.json";
    private static final String GROUP = "YUNXI_CONFIG_GROUP";

    private static ConfigService nacos;
    private static String serverAddr;

    @BeforeAll
    static void connect() throws NacosException {
        serverAddr = System.getenv().getOrDefault("NACOS_SERVER_ADDR", "127.0.0.1:8848");
        String namespace = System.getenv().getOrDefault("NACOS_NAMESPACE", "public");

        Properties props = new Properties();
        props.setProperty("serverAddr", serverAddr);
        props.setProperty("namespace", namespace);
        try {
            nacos = NacosFactory.createConfigService(props);
        } catch (NacosException e) {
            Assumptions.assumeTrue(false, "无法创建 Nacos ConfigService（" + serverAddr + "）：" + e.getMessage());
        }
        // 懒连接：createConfigService 不会立即建连；不可达时客户端长期停留在 STARTING，getServerStatus() 不会变 UP。
        // 轮询真实连接状态，仅当 UP 才执行测试；否则整类跳过（CI 中 Nacos 在线时执行）。
        boolean up = false;
        for (int i = 0; i < 50; i++) {
            if ("UP".equals(nacos.getServerStatus())) {
                up = true;
                break;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Assumptions.assumeTrue(up,
                "Nacos 不可达（" + serverAddr + "，状态非 UP），跳过 Nacos 集成测试（CI 中 Nacos 在线时执行）");
    }

    @AfterAll
    static void cleanup() {
        if (nacos == null) {
            return;
        }
        try {
            if ("UP".equals(nacos.getServerStatus())) {
                nacos.removeConfig(DATA_ID, GROUP);
            }
        } catch (Exception ignored) {
            // 清理期异常不应掩盖测试结论
        } finally {
            try {
                nacos.shutDown();
            } catch (Exception ignored) {
                // 客户端未连上时 shutDown 可能抛错，忽略
            }
        }
    }

    @Test
    void registerPersistsToAllocationDataId_andReadsBack() throws NacosException {
        ConfigCenterNacosProperties props = new ConfigCenterNacosProperties();
        props.setEnabled(true);
        props.setAllocationDataId(DATA_ID);
        props.setGroup(GROUP);
        NacosBackedConfigCenter cc = new NacosBackedConfigCenter(nacos, props);

        TenantAgentAllocation alloc = new TenantAgentAllocation(
                Map.of("it-tenant", Set.of("it-a1")), Set.of());
        cc.registerTenantAllocation(alloc);

        // 真实 Nacos 往返：写后回读一致
        assertEquals(Set.of("it-a1"),
                cc.getTenantAllocation().resolve("it-tenant").orElseThrow());

        // 远端直接推送变更 → ctor 注册的监听异步热加载
        nacos.publishConfig(DATA_ID, GROUP,
                "{\"entries\":{\"it-tenant\":[\"it-a1\",\"it-a2\"]},\"defaultAgents\":[]}");

        boolean reloaded = false;
        for (int i = 0; i < 50; i++) {
            if (cc.getTenantAllocation().resolve("it-tenant").orElse(Set.of()).contains("it-a2")) {
                reloaded = true;
                break;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(reloaded, "远端推送后，监听应在超时内热加载新分配（it-a2 应出现）");
    }
}
