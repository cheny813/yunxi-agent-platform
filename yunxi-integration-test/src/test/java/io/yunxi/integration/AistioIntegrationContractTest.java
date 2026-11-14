package io.yunxi.integration;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * aistio 集成接线门禁：锁定 yunxi-fabric.aistio 集成点（AistioConnector）三项状态对应的真实源码接线，
 * 防止「文档记未闭环但代码已闭环 / 代码被静默回退」的长期漂移。
 *
 * <p>与开源卫生门禁不同，本测试不扫描全仓，只针对 yunxi-fabric 单一集成点 io.yunxi.fabric.aistio 的
 * 两个源文件做结构断言。任何框架升级或重构致本集成点状态变化，须同步更新本类断言，二者不一致即 CI 失败。</p>
 */
class AistioIntegrationContractTest {

    @Test
    void workspaceDefinitionV1CapabilityIsWired() throws IOException {
        String src = readAistioSource("AistioConnector.java");
        Assertions.assertTrue(src.contains("withWorkspaceFactory("),
                "item1: AistioConnector 必须经 withWorkspaceFactory 声明 workspace-definition-v1 能力，"
                        + "不得回退为不装配（否则带 definition 的派发会永久 skip 本实例）");
    }

    @Test
    void instanceKeyIsStableNotUuid() throws IOException {
        String src = readAistioSource("AistioConnector.java");
        Assertions.assertTrue(src.contains("app + \"-\" + host + \"-\" + port"),
                "item2: instanceKey 必须取 ${app}-${host}-${port} 稳定唯一形态");
        Assertions.assertFalse(src.contains("UUID.randomUUID()"),
                "item2: instanceKey 不得回退为框架默认 UUID.randomUUID()");
    }

    @Test
    void correlationIdFallbackIsCaptured() throws IOException {
        String src = readAistioSource("YunxiFrameworkAdapter.java");
        Assertions.assertTrue(src.contains("_yunxi") && src.contains("correlationIds.put"),
                "item3: correlationId 必须自 payload 回读并存索引+日志兜底，"
                        + "不得静默丢弃，也不得借 patch 框架绕过产品侧兜底进入 RuntimeContext");
    }

    private static String readAistioSource(String fileName) throws IOException {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("yunxi-fabric")
                    .resolve("src/main/java/io/yunxi/fabric/aistio").resolve(fileName);
            if (Files.exists(candidate)) {
                return String.join("\n", Files.readAllLines(candidate));
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未找到 aistio 源文件: " + fileName);
    }
}
