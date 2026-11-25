package io.yunxi.runtime.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.yunxi.runtime.skilluse.SkillUseEvent;
import io.yunxi.runtime.skilluse.SkillUseEventType;
import io.yunxi.runtime.skilluse.SkillUseEventStore;
import io.yunxi.runtime.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 技能使用事件记录中间件单测：拦截口径（VIEW/USE 工具名）、归属提取、best-effort 语义。
 */
class SkillUseEventRecorderTest {

    /** 内存收集桩：可注入故障模拟 best-effort。 */
    private static final class CapturingStore implements SkillUseEventStore {
        final List<SkillUseEvent> events = new ArrayList<>();
        boolean failOnRecord;

        @Override
        public void record(SkillUseEvent event) {
            if (failOnRecord) {
                throw new IllegalStateException("simulated store failure");
            }
            events.add(event);
        }

        @Override
        public Map<String, io.yunxi.runtime.skilluse.SkillUsageStats> statsFor(java.util.Collection<String> skills) {
            return Map.of();
        }
    }

    private final CapturingStore store = new CapturingStore();
    private final SkillUseEventRecorder recorder = new SkillUseEventRecorder(store);
    /** 下游是否被放行（onion 链继续执行）。 */
    boolean nextInvoked;

    @AfterEach
    void cleanTenant() {
        TenantContext.clear();
    }

    private void run(ToolUseBlock... calls) {
        ActingInput input = new ActingInput(List.of(calls));
        RuntimeContext ctx = RuntimeContext.builder().userId("alice").sessionId("s1").build();
        recorder.onActing(null, ctx, input, i -> {
            nextInvoked = true;
            return Flux.<io.agentscope.core.event.AgentEvent>empty();
        }).then().block();
    }

    @Test
    void viewToolRecordsViewEventWithAttribution() {
        TenantContext.set("tenant-a");
        run(new ToolUseBlock("id1", "load_skill_through_path", Map.of("skillId", "weather")));

        assertEquals(1, store.events.size());
        SkillUseEvent e = store.events.get(0);
        assertEquals("weather", e.skill());
        assertEquals(SkillUseEventType.VIEW, e.eventType());
        assertEquals("tenant-a", e.tenantId());
        assertEquals("alice", e.userId());
    }

    @Test
    void useSkillToolRecordsUseEvent() {
        run(new ToolUseBlock("id1", "use_skill", Map.of("skill_id", "sql-helper")));
        assertEquals(1, store.events.size());
        assertEquals(SkillUseEventType.USE, store.events.get(0).eventType());
        assertEquals("sql-helper", store.events.get(0).skill());
    }

    @Test
    void readSkillCountsAsView() {
        run(new ToolUseBlock("id1", "read_skill", Map.of("name", "chart")));
        assertEquals(1, store.events.size());
        assertEquals(SkillUseEventType.VIEW, store.events.get(0).eventType());
    }

    @Test
    void nonSkillToolIsIgnored() {
        run(new ToolUseBlock("id1", "write_file", Map.of("path", "x.txt")));
        assertTrue(store.events.isEmpty());
    }

    @Test
    void skillToolWithoutSkillIdIsIgnored() {
        run(new ToolUseBlock("id1", "load_skill_through_path", Map.of("other", "v")));
        assertTrue(store.events.isEmpty());
    }

    @Test
    void storeFailureNeverBreaksAgentLoop() {
        store.failOnRecord = true;
        run(new ToolUseBlock("id1", "load_skill_through_path", Map.of("skillId", "weather")));
        assertTrue(nextInvoked);
    }
}
