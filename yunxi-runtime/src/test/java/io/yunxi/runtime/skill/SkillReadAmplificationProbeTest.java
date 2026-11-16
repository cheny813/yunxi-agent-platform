package io.yunxi.runtime.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.skill.JdbcAgentSkillRepository;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.middleware.HarnessSkillMiddleware;
import io.agentscope.harness.agent.skill.runtime.ShellPathPolicy;
import io.yunxi.runtime.StubTextModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 技能读放大探针：量化「每次 agent pass 对各层技能仓库各做一次全量直读」的放大倍数与单次读成本。
 *
 * <p><b>测量命题</b>：框架 {@code HarnessSkillMiddleware} 非冻结模式下，每次 {@code call()} 的
 * ON_SYSTEM_PROMPT 阶段都会对每一层仓库各做一次全量 {@code getAllSkills()}（框架在 {@code AgentBase.preCall}
 * 中一次性播种系统消息，故每次 call 只触发一次；{@code mergeRepositories} 逐仓库全量读，仓库读取结果不跨
 * 调用缓存）。因此读放大为纯线性：
 *
 * <pre>
 *   全量读次数 / pass = 已接线层数 L
 *   返回行数   / pass = Σ(各层技能数)
 *   全量读次数 / 秒   = QPS × L
 *  </pre>
 *
 * <p><b>用途</b>：作为「技能读缓存」立项触发条件的实测证据——本探针同时测量框架原生快照机制
 * （{@code HarnessSkillMiddleware.frozen(...)}，构建期读一次、pass 内零读）作为对照基线，量化缓存收益上限。
 *
 * <p><b>口径说明</b>：
 * <ul>
 *   <li>读次数/行数为确定性断言（与机器性能无关）；</li>
 *   <li>耗时仅记录不断言——依赖机器性能，放文档结论里作参考量级；</li>
 *   <li>MySQL 后端测量默认跳过，需 {@code -Dyunxi.e2e.jdbc=true} 显式开启（连接参数同
 *       {@code SkillRepositoryJdbcE2eTest}）。</li>
 * </ul>
 *
 * <p>生产层数 L 的来源（框架 {@code composeSkillRepositories} 与 yunxi 装配）：
 * L1 项目全局技能目录（存在才加）、L2 用户仓库（builtin 19 个 / 团队 Git / 用户 JDBC）、L3 workspace 共享目录
 * （存在才加）、L4 每 agent workspace 仓库（始终）。故基线 L=2（builtin + workspace L4），全开后 L=4。
 */
class SkillReadAmplificationProbeTest {

    private static final String PREFIX = "[read-amplification probe] ";

    // ---------------------------------------------------------------------
    // 机制层：读次数 / 行数的确定性断言
    // ---------------------------------------------------------------------

    @Test
    void onePassReadsEachWiredLayerExactlyOnce() {
        CountingRepository builtinLike = CountingRepository.inMemory("probe-builtin", 19);
        CountingRepository jdbcLike = CountingRepository.inMemory("probe-jdbc", 50);
        HarnessSkillMiddleware middleware =
                new HarnessSkillMiddleware(List.of(builtinLike, jdbcLike), new Toolkit());

        String prompt = middleware.onSystemPrompt(null, ctx("pass-1"), "").block();

        assertNotNull(prompt, "系统提示应生成成功");
        assertEquals(1, builtinLike.fullReads(), "每 pass 每层一次全量读");
        assertEquals(1, jdbcLike.fullReads(), "每 pass 每层一次全量读");
        assertEquals(19, builtinLike.rowsRead());
        assertEquals(50, jdbcLike.rowsRead());
        assertEquals(0, builtinLike.pointReads(), "全量读路径不应触发按名读取");
        assertTrue(prompt.contains("probe-builtin-1"), "19 条内置技能应全部进入提示");
        assertTrue(prompt.contains("probe-jdbc-50"), "50 条 JDBC 技能应全部进入提示");

        report("单 pass / 2 层", 2, 1, 69);
    }

    @Test
    void readsGrowLinearlyWithPassesAndRowsEveryTime() {
        CountingRepository builtinLike = CountingRepository.inMemory("probe-builtin", 19);
        CountingRepository jdbcLike = CountingRepository.inMemory("probe-jdbc", 50);
        HarnessSkillMiddleware middleware =
                new HarnessSkillMiddleware(List.of(builtinLike, jdbcLike), new Toolkit());

        int passes = 5;
        for (int i = 1; i <= passes; i++) {
            middleware.onSystemPrompt(null, ctx("session-" + i), "").block();
        }

        // 内容完全未变，仍每 pass 全量重读：仓库结果无跨 pass 缓存
        assertEquals(passes, builtinLike.fullReads());
        assertEquals(passes, jdbcLike.fullReads());
        assertEquals(passes * 19L, builtinLike.rowsRead());
        assertEquals(passes * 50L, jdbcLike.rowsRead());

        report("5 个 pass / 2 层", 2, passes, passes * 69);
    }

    @Test
    void frozenSnapshotReadsNothingPerPass() {
        CountingRepository builtinLike = CountingRepository.inMemory("probe-frozen", 19);
        HarnessSkillMiddleware frozen = HarnessSkillMiddleware.frozen(
                List.of(builtinLike), new Toolkit(), null, null, null, ShellPathPolicy.noShell());

        assertEquals(1, builtinLike.fullReads(), "冻结快照在构建期读一次");

        int passes = 5;
        for (int i = 1; i <= passes; i++) {
            String prompt = frozen.onSystemPrompt(null, ctx("frozen-" + i), "").block();
            assertNotNull(prompt);
            assertTrue(prompt.contains("probe-frozen-1"), "冻结后仍从快照渲染全部技能");
        }
        assertEquals(1, builtinLike.fullReads(), "冻结后 pass 内零仓库读");

        report("冻结快照 / 5 个 pass", 1, 0, 0);
    }

    @Test
    void realCallPathFiresExactlyOneSkillPassPerCall(@TempDir Path workspace) {
        CountingRepository repo = CountingRepository.inMemory("probe-call", 19);
        HarnessAgent agent = HarnessAgent.builder()
                .name("probe-agent")
                .model(new StubTextModel("ok"))
                .workspace(workspace)
                .abstractFilesystem(new LocalFilesystem(workspace))
                .skillRepository(repo)
                .build();

        Msg first = agent.call("hello", ctx("call-1")).block();
        assertNotNull(first);
        assertEquals(1, repo.fullReads(), "一次 call 只触发一次技能全量读（与 ReAct 迭代次数无关）");

        Msg second = agent.call("hello", ctx("call-2")).block();
        assertNotNull(second);
        assertEquals(2, repo.fullReads(), "第二次 call 再读一次，逐 call 累加");

        report("真实 call 路径 / 2 次 call", 1, 2, 2 * 19);
    }

    // ---------------------------------------------------------------------
    // 成本测量：单次全量读耗时（统计性，仅记录）
    // ---------------------------------------------------------------------

    @Test
    void measureFullReadCostMemoryVersusFileSystem(@TempDir Path tempDir) throws IOException {
        int skillCount = 50;

        CountingRepository memory = CountingRepository.inMemory("probe-mem", skillCount);
        double memoryMs = averagePassMillis(
                new HarnessSkillMiddleware(List.of(memory), new Toolkit()), 200);

        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);
        writeSkillFiles(skillsDir, "probe-fs", skillCount);
        CountingRepository fileSystem = CountingRepository.decorating(
                new FileSystemSkillRepository(skillsDir, false, "probe-fs"), "probe-fs");
        double fileSystemMs = averagePassMillis(
                new HarnessSkillMiddleware(List.of(fileSystem), new Toolkit()), 20);

        assertEquals(skillCount, memory.rowsRead() / memory.fullReads());
        assertEquals(skillCount, fileSystem.rowsRead() / fileSystem.fullReads());

        System.out.printf(
                Locale.ROOT,
                "%s单层 %d 技能每 pass（ON_SYSTEM_PROMPT 全流程，含提示渲染）均值："
                        + "内存后端 %.3f ms，本地 FS 后端（框架 mtime 缓存已预热）%.3f ms%n",
                PREFIX,
                skillCount,
                memoryMs,
                fileSystemMs);
    }

    @Test
    void measureFullReadCostOnMySql() throws Exception {
        Assumptions.assumeTrue(
                Boolean.getBoolean("yunxi.e2e.jdbc"),
                "跳过：未设置 -Dyunxi.e2e.jdbc=true");

        String url = System.getProperty(
                "yunxi.e2e.jdbc.url",
                "jdbc:mysql://127.0.0.1:3306/yunxi_agent_platform?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true");
        String user = System.getProperty("yunxi.e2e.jdbc.user", "root");
        String password = System.getProperty("yunxi.e2e.jdbc.password", "root");

        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(user);
        dataSource.setPassword(password);
        dataSource.setPoolName("yunxi-skill-probe");

        String ddlPath = System.getProperty(
                "yunxi.e2e.skillSql", "../deploy/infra/init/skill-store.sql");
        String ddl = Files.readString(Path.of(ddlPath));
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            for (String stmt : ddl.split(";")) {
                if (!stmt.trim().isEmpty()) {
                    st.execute(stmt.trim());
                }
            }
        }

        AbstractJdbcDialect dialect =
                AbstractJdbcDialect.from(dataSource).enableSkillTables(true).build();
        JdbcAgentSkillRepository jdbcRepository = new JdbcAgentSkillRepository(dataSource, dialect, true);

        int skillCount = 50;
        List<AgentSkill> seeded = IntStream.rangeClosed(1, skillCount)
                .mapToObj(i -> new AgentSkill(
                        "probe-jdbc-" + i,
                        "probe skill " + i,
                        "# probe " + i + "\nbody " + i,
                        Map.of(),
                        "jdbc"))
                .toList();
        try {
            jdbcRepository.save(seeded, true);
            CountingRepository counted = CountingRepository.decorating(jdbcRepository, "probe-jdbc-mysql");
            double jdbcMs = averagePassMillis(
                    new HarnessSkillMiddleware(List.of(counted), new Toolkit()), 20);

            System.out.printf(
                    Locale.ROOT,
                    "%s单层 %d 技能每 pass（ON_SYSTEM_PROMPT 全流程，MySQL 直读无缓存）均值：%.3f ms%n",
                    PREFIX,
                    skillCount,
                    jdbcMs);
        } finally {
            for (AgentSkill skill : seeded) {
                jdbcRepository.delete(skill.getName());
            }
            jdbcRepository.close();
            dataSource.close();
        }
    }

    // ---------------------------------------------------------------------
    // 生产放大公式（层数与行数取自代码事实：builtin 19 个 / 层数 2~4）
    // ---------------------------------------------------------------------

    @Test
    void reportProductionAmplificationFormula() {
        int baselineLayers = 2; // builtin classpath + 每 agent workspace 仓库（L4 始终）
        int fullLayers = 4; // 再加团队 Git + 用户 JDBC（均默认关闭）
        int builtinRows = 19;

        for (int qps : new int[] {10, 50, 200}) {
            System.out.printf(
                    Locale.ROOT,
                    "%s生产投影：QPS=%d → 全量读 %d~%d 次/秒，行数下限 %d~%d 行/秒%n",
                    PREFIX,
                    qps,
                    qps * baselineLayers,
                    qps * fullLayers,
                    qps * baselineLayers * builtinRows,
                    qps * fullLayers * builtinRows);
        }
    }

    // ---------------------------------------------------------------------
    // 工具方法
    // ---------------------------------------------------------------------

    private static RuntimeContext ctx(String sessionId) {
        return RuntimeContext.builder().userId("probe-user").sessionId(sessionId).build();
    }

    private static double averagePassMillis(HarnessSkillMiddleware middleware, int rounds) {
        // 预热一次（本地 FS 后端的 mtime 缓存填充）
        middleware.onSystemPrompt(null, ctx("warm-up"), "").block();
        long start = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            middleware.onSystemPrompt(null, ctx("measure-" + i), "").block();
        }
        return (System.nanoTime() - start) / 1_000_000.0 / rounds;
    }

    private static void writeSkillFiles(Path dir, String prefix, int count) throws IOException {
        for (int i = 1; i <= count; i++) {
            Path skillDir = dir.resolve(prefix + "-" + i);
            Files.createDirectories(skillDir);
            Files.writeString(
                    skillDir.resolve("SKILL.md"),
                    "---\nname: " + prefix + "-" + i + "\ndescription: probe skill " + i
                            + "\n---\n\n# body\ncontent " + i + "\n");
        }
    }

    private static void report(String scenario, int layers, int passes, long rows) {
        System.out.printf(
                Locale.ROOT,
                "%s%s：层数=%d，pass 数=%d → 全量读 %d 次，返回 %d 行%n",
                PREFIX,
                scenario,
                layers,
                passes,
                (long) layers * passes,
                rows);
    }

    /**
     * 计数装饰器：记录全量读（{@link #getAllSkills()}）次数、返回行数、按名读取次数与耗时。
     * 直接实现框架 {@link AgentSkillRepository}，不改变被装饰仓库的任何行为。
     */
    private static final class CountingRepository implements AgentSkillRepository {

        private final AgentSkillRepository delegate;
        private final String source;
        private final AtomicInteger fullReads = new AtomicInteger();
        private final AtomicLong rowsRead = new AtomicLong();
        private final AtomicInteger pointReads = new AtomicInteger();
        private final LongAdder fullReadNanos = new LongAdder();

        private CountingRepository(AgentSkillRepository delegate, String source) {
            this.delegate = delegate;
            this.source = source;
        }

        static CountingRepository inMemory(String source, int skillCount) {
            List<AgentSkill> skills = IntStream.rangeClosed(1, skillCount)
                    .mapToObj(i -> new AgentSkill(
                            source + "-" + i,
                            "probe skill " + i,
                            "# probe " + i + "\nbody " + i,
                            Map.of(),
                            source))
                    .toList();
            return new CountingRepository(new InMemoryRepository(source, skills), source);
        }

        static CountingRepository decorating(AgentSkillRepository delegate, String source) {
            return new CountingRepository(delegate, source);
        }

        int fullReads() {
            return fullReads.get();
        }

        long rowsRead() {
            return rowsRead.get();
        }

        int pointReads() {
            return pointReads.get();
        }

        @Override
        public AgentSkill getSkill(String name) {
            pointReads.incrementAndGet();
            return delegate.getSkill(name);
        }

        @Override
        public List<String> getAllSkillNames() {
            return delegate.getAllSkillNames();
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            long start = System.nanoTime();
            try {
                List<AgentSkill> skills = delegate.getAllSkills();
                fullReads.incrementAndGet();
                rowsRead.addAndGet(skills.size());
                return skills;
            } finally {
                fullReadNanos.add(System.nanoTime() - start);
            }
        }

        @Override
        public boolean save(List<AgentSkill> skills, boolean force) {
            return delegate.save(skills, force);
        }

        @Override
        public boolean delete(String skillName) {
            return delegate.delete(skillName);
        }

        @Override
        public boolean skillExists(String skillName) {
            return delegate.skillExists(skillName);
        }

        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return delegate.getRepositoryInfo();
        }

        @Override
        public String getSource() {
            return source;
        }

        @Override
        public void setWriteable(boolean writeable) {
            delegate.setWriteable(writeable);
        }

        @Override
        public boolean isWriteable() {
            return delegate.isWriteable();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /** 最小内存仓库：只为探针提供确定性的技能集合。 */
    private static final class InMemoryRepository implements AgentSkillRepository {

        private final String source;
        private final List<AgentSkill> skills;

        private InMemoryRepository(String source, List<AgentSkill> skills) {
            this.source = source;
            this.skills = new ArrayList<>(skills);
        }

        @Override
        public AgentSkill getSkill(String name) {
            return skills.stream().filter(s -> s.getName().equals(name)).findFirst().orElse(null);
        }

        @Override
        public List<String> getAllSkillNames() {
            return skills.stream().map(AgentSkill::getName).toList();
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            return List.copyOf(skills);
        }

        @Override
        public boolean save(List<AgentSkill> skills, boolean force) {
            return false;
        }

        @Override
        public boolean delete(String skillName) {
            return false;
        }

        @Override
        public boolean skillExists(String skillName) {
            return skills.stream().anyMatch(s -> s.getName().equals(skillName));
        }

        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return new AgentSkillRepositoryInfo("probe", source, false);
        }

        @Override
        public String getSource() {
            return source;
        }

        @Override
        public void setWriteable(boolean writeable) {
            // 只读探针仓库
        }

        @Override
        public boolean isWriteable() {
            return false;
        }
    }
}
