package io.yunxi.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.extensions.jdbc.skill.JdbcAgentSkillRepository;
import io.yunxi.intelligence.muse.prune.UsageProfileAggregator;
import io.yunxi.intelligence.muse.tool.SkillPruneTool;
import io.yunxi.runtime.middleware.SkillUseEventRecorder;
import io.yunxi.runtime.skilluse.JdbcSkillUseEventStore;
import io.yunxi.runtime.skilluse.SkillUseEventStore;
import io.yunxi.runtime.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;

/**
 * 端到端验证：技能使用遥测全链路——运行时中间件记录技能加载/调用事件 → JDBC 事件表
 * （H2 承载 skillStoreDataSource，MySQL 兼容模式）→ 聚合器服务端覆盖 → 剪枝元工具决策。
 *
 * <p>同时验证开关联动语义：仅开启 yunxi.skill.jdbc.enabled（不设 yunxi.muse.usage.enabled），
 * 遥测应自动启用。模型自报 usage_count 在服务端真相面前失效——本测试用自报 50 证实服务端覆盖。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "yunxi.security.user-name=admin",
        "yunxi.security.user-password=test-secret",
        "yunxi.skill.jdbc.enabled=true",
        "yunxi.skill.jdbc.url=jdbc:h2:mem:e2e-muse;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "yunxi.skill.jdbc.username=sa"
})
class MuseUsageTruthE2eTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private SkillUseEventRecorder recorder;

    @Autowired
    private SkillPruneTool pruneTool;

    @AfterEach
    void cleanTenant() {
        TenantContext.clear();
    }

    @Test
    void linkageEnablesUsageBeansAndRealJdbcSkillRepository() {
        SkillUseEventStore store = context.getBean(SkillUseEventStore.class);
        assertTrue(store instanceof JdbcSkillUseEventStore,
                "联动语义：仅开启 skill.jdbc 即应装配 JDBC 事件存储");
        assertTrue(context.getBeanNamesForType(UsageProfileAggregator.class).length > 0,
                "联动语义：聚合器应随 skill.jdbc 自动装配");
        // 真实框架 JDBC 技能仓库随同一数据源装配（H2 方言自动探测），证明数据面整链可用
        assertTrue(context.getBeanNamesForType(JdbcAgentSkillRepository.class).length > 0,
                "skill.jdbc.enabled=true 应装配框架 JDBC 技能仓库");
    }

    @Test
    void recorderLandsEventsInJdbcTable() {
        record(load("e2e-view-a"), "alice");
        record(load("e2e-view-a"), "bob");
        record(use("e2e-use-a"), "alice");

        JdbcSkillUseEventStore store = context.getBean(JdbcSkillUseEventStore.class);
        // 事件已真实落表：2 次调用、2 个去重用户
        assertTrue(store.statsFor(List.of("e2e-view-a")).get("e2e-view-a").distinctUsers() == 2,
                "中间件记录的事件应真实写入 JDBC 事件表");
        assertTrue(store.statsFor(List.of("e2e-view-a")).get("e2e-view-a").useCount() == 2,
                "VIEW/USE 事件应按调用意向语义累计");
    }

    @Test
    void pruneDecisionsDrivenByServerTruthNotSelfReport() {
        // 预置服务端真相（技能名与上一测试隔离，避免同库跨测试污染）：
        // e2e-keep 2 次 2 人（保留）、e2e-hot 6 次 1 人（过拟合）、e2e-ghost 0 次（归档）
        record(load("e2e-keep"), "alice");
        record(load("e2e-keep"), "bob");
        for (int i = 0; i < 6; i++) {
            record(load("e2e-hot"), "alice");
        }

        // 模型自报全部 usage_count=50（谎言）
        String output = pruneTool.prune("["
                + "{\"name\":\"e2e-keep\",\"usage_count\":50,\"content\":\"weather query skill\"},"
                + "{\"name\":\"e2e-hot\",\"usage_count\":50,\"content\":\"hot single user tool\"},"
                + "{\"name\":\"e2e-ghost\",\"usage_count\":50,\"content\":\"ghost unused skill\"}"
                + "]");

        // e2e-ghost：服务端 0 次（< minUsage=1）→ 归档。自报 50 本应保留——归档出现即证明服务端覆盖生效
        assertTrue(output.contains("e2e-ghost"), "剪枝输出应包含 ghost 决策");
        assertTrue(output.contains("使用次数 0"), "ghost 应按服务端 0 次归档（自报 50 被覆盖）");
        // e2e-hot：服务端 6 次/1 人 → 画像判据标记过拟合（自报 50 + 无画像本不会触发）
        assertTrue(output.contains("过拟合"), "hot 应被画像判据标记过拟合（distinctUsers=1 且服务端 6 次）");
        // e2e-keep：服务端 2 次（>= minUsage）→ 不产生 ARCHIVE 决策行
        assertFalse(linesFor(output, "e2e-keep").stream().anyMatch(l -> l.contains("ARCHIVE")),
                "e2e-keep 服务端 2 次不应被归档");
    }

    private List<String> linesFor(String output, String skill) {
        List<String> lines = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (line.contains(skill)) {
                lines.add(line);
            }
        }
        return lines;
    }

    private void record(ToolUseBlock call, String userId) {
        TenantContext.set("tenant-e2e");
        RuntimeContext ctx = RuntimeContext.builder().userId(userId).sessionId("e2e").build();
        Function<ActingInput, Flux<AgentEvent>> next = input -> Flux.empty();
        recorder.onActing(null, ctx, new ActingInput(List.of(call)), next).then().block();
    }

    private ToolUseBlock load(String skill) {
        return new ToolUseBlock("id-" + skill + "-" + System.nanoTime(), "load_skill_through_path",
                Map.of("skillId", skill));
    }

    private ToolUseBlock use(String skill) {
        return new ToolUseBlock("id-" + skill + "-" + System.nanoTime(), "use_skill",
                Map.of("skill_id", skill));
    }
}
