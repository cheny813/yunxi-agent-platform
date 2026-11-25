package io.yunxi.runtime.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yunxi.definition.AgentSpec;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 聚焦单测：锁定 {@link DeadToolNameAdvisor} 的死名检出与候选名建议。
 * 权限清单是字面匹配：写错名字不报错、规则永不生效（模板 memory 案例的教训），
 * 本顾问在装配期对声明名与终态工具名单比对，把「写了但永不命中」变成启动期可看见的告警。
 */
class DeadToolNameAdvisorTest {

    private static final Set<String> KNOWN = Set.of(
            "memory_search", "memory_get", "memory_save", "read_file", "write_file",
            "execute", "shell", "grep_files", "todo_write");

    @Test
    void deadNameReported_withoutSuggestion_whenFarFromAnyRealName() {
        // memory 与任何实名都太远（距离/长度 > 0.3），只报死名不猜候选——乱猜比不猜更误导；
        // 死名报告本身已是主要价值：告知「该规则永不生效」。
        AgentSpec.PermissionConfig cfg = new AgentSpec.PermissionConfig(
                null, null, List.of("memory_search", "memory"), null);

        List<DeadToolNameAdvisor.DeadName> dead = DeadToolNameAdvisor.findDeadNames(cfg, KNOWN);

        assertEquals(1, dead.size());
        assertEquals("memory", dead.get(0).declared());
        assertNull(dead.get(0).suggestion());
    }

    @Test
    void nearMiss_getsSuggestion() {
        // 少打一个下划线，距离足够近，给出最可能想要的名字。
        AgentSpec.PermissionConfig cfg = new AgentSpec.PermissionConfig(
                null, null, List.of("memory_serch"), null);

        List<DeadToolNameAdvisor.DeadName> dead = DeadToolNameAdvisor.findDeadNames(cfg, KNOWN);

        assertEquals(1, dead.size());
        assertEquals("memory_search", dead.get(0).suggestion());
    }

    @Test
    void allRealNames_noReport() {
        AgentSpec.PermissionConfig cfg = new AgentSpec.PermissionConfig(
                null, null, List.of("memory_search", "read_file"), List.of("execute"));

        assertTrue(DeadToolNameAdvisor.findDeadNames(cfg, KNOWN).isEmpty(), "全部实名不得误报");
    }

    @Test
    void allThreeListsScanned_includingGated() {
        // deniedTools / toolGate.tools 与 allowedTools 同样字面匹配，任一清单写错都同样静默，
        // 须一并扫描。reasoningReview 的清单是触发复核的关键词而非工具名，不参与比对。
        AgentSpec.PermissionConfig cfg = new AgentSpec.PermissionConfig(
                new AgentSpec.PermissionConfig.ToolGate(true, List.of("shell", "grep_fles")),
                new AgentSpec.PermissionConfig.ReasoningReview(true, "strict", List.of("任写关键词也不算死名")),
                List.of("memory_serch"), List.of("writ_file"));

        List<DeadToolNameAdvisor.DeadName> dead = DeadToolNameAdvisor.findDeadNames(cfg, KNOWN);

        assertEquals(3, dead.size(), "三个工具名清单都要扫：allowed/denied/toolGate");
        assertTrue(dead.stream().anyMatch(d ->
                "memory_serch".equals(d.declared()) && "memory_search".equals(d.suggestion())));
        assertTrue(dead.stream().anyMatch(d ->
                "writ_file".equals(d.declared()) && "write_file".equals(d.suggestion())));
        assertTrue(dead.stream().anyMatch(d ->
                "grep_fles".equals(d.declared()) && "grep_files".equals(d.suggestion())));
    }

    @Test
    void nullPermission_noReport() {
        assertTrue(DeadToolNameAdvisor.findDeadNames(null, KNOWN).isEmpty(), "无声明不得 NPE、不得误报");
    }
}
