package io.yunxi.runtime.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 针对 {@link SessionToolGroupRegistry} 的聚焦单测：登记 / 查询 / 覆盖 / 拷贝语义。
 *
 * <p>本登记处是 build 期单次写入、调用期只读的 Spring Bean，不持有第二条横切真相。
 */
class SessionToolGroupRegistryTest {

    @Test
    void registerThenPlanForReturnsRegisteredGroups() {
        SessionToolGroupRegistry registry = new SessionToolGroupRegistry();
        registry.register("agent-a", List.of("g1", "g2"));
        assertEquals(List.of("g1", "g2"), registry.planFor("agent-a"));
    }

    @Test
    void planForUnknownAgentReturnsEmptyList() {
        SessionToolGroupRegistry registry = new SessionToolGroupRegistry();
        assertTrue(registry.planFor("missing").isEmpty(),
                "未知 Agent 须返回空列表（中间据此跳过），而非 null");
    }

    @Test
    void registerOverwritesPreviousGroups() {
        SessionToolGroupRegistry registry = new SessionToolGroupRegistry();
        registry.register("agent-a", List.of("g1"));
        registry.register("agent-a", List.of("g2", "g3"));
        assertEquals(List.of("g2", "g3"), registry.planFor("agent-a"),
                "重复 register 须整体覆盖，而非追加");
    }

    @Test
    void registerCopiesArgumentToAvoidExternalMutation() {
        SessionToolGroupRegistry registry = new SessionToolGroupRegistry();
        List<String> src = new ArrayList<>(List.of("g1"));
        registry.register("agent-a", src);
        src.add("g2");
        assertEquals(List.of("g1"), registry.planFor("agent-a"),
                "register 须拷贝入参，不受外部列表后续改动影响");
    }
}
