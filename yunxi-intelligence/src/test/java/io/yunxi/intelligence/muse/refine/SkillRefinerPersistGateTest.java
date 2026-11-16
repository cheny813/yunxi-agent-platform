package io.yunxi.intelligence.muse.refine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.agentscope.harness.agent.gateway.TurnBusyException;
import io.agentscope.harness.agent.gateway.TurnLease;
import io.yunxi.intelligence.muse.config.EvolutionConfig;
import io.yunxi.runtime.DistributedRuntimeConfig;
import io.yunxi.runtime.ModelFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/**
 * MUSE 单写者：persist 写路径须按技能名粒度进轮次闸门（跨实例互斥，防多副本演进互相覆盖）；
 * 闸门忙时快速返回 false（MUSE 后台任务可重试，不得阻塞、不得半写）。
 */
class SkillRefinerPersistGateTest {

    private final DistributedRuntimeConfig runtimeConfig = mock(DistributedRuntimeConfig.class);
    private final SessionTurnGate gate = mock(SessionTurnGate.class);
    private final AgentSkillRepository repo = mock(AgentSkillRepository.class);
    private final ModelFactory modelFactory = mock(ModelFactory.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<AgentSkillRepository> repoProvider = mock(ObjectProvider.class);

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        when(runtimeConfig.sessionTurnGate()).thenReturn(gate);
        when(repoProvider.getIfAvailable()).thenReturn(repo);
    }

    private SkillRefiner refiner() {
        return new SkillRefiner(modelFactory, repoProvider, new EvolutionConfig(), runtimeConfig);
    }

    @Test
    void persistWritesUnderLease() throws Exception {
        TurnLease lease = mock(TurnLease.class);
        when(gate.acquire(anyString())).thenReturn(lease);
        String dir = tempDir.resolve("skill-a").toString();

        boolean saved = refiner().persist("skill-a", "refined content", dir);

        assertTrue(saved, "闸门空闲时应完成保存");
        assertTrue(Files.exists(Path.of(dir, "SKILL.md")), "磁盘正文应写回（评估器读磁盘）");
        verify(repo).save(any(), Mockito.eq(true));
        verify(lease).close();
    }

    @Test
    void persistSkipsFastWhenGateBusy() throws Exception {
        when(gate.acquire(anyString()))
                .thenThrow(new TurnBusyException("turn busy: muse:skill:default:skill-a"));
        String dir = tempDir.resolve("skill-a").toString();

        boolean saved = refiner().persist("skill-a", "refined content", dir);

        assertFalse(saved, "闸门忙时应快速返回，等待下一轮重试");
        assertFalse(Files.exists(Path.of(dir)), "忙时不得半写磁盘");
        verify(repo, never()).save(any(), Mockito.eq(true));
    }

    @Test
    void persistReleasesLeaseWhenWritingThrows() throws Exception {
        TurnLease lease = mock(TurnLease.class);
        when(gate.acquire(anyString())).thenReturn(lease);
        doThrow(new RuntimeException("disk failure"))
                .when(repo).save(any(), Mockito.eq(true));
        String dir = tempDir.resolve("skill-a").toString();

        boolean saved = refiner().persist("skill-a", "refined content", dir);

        assertFalse(saved, "写入失败不应伪装成功");
        verify(lease).close();
    }

    @Test
    void persistRestoresInterruptFlag() throws Exception {
        when(gate.acquire(anyString())).thenThrow(new InterruptedException("shutdown"));
        String dir = tempDir.resolve("skill-a").toString();

        Thread runner = new Thread(() -> refiner().persist("skill-a", "c", dir));
        runner.start();
        runner.join(5000);
        assertFalse(runner.isAlive(), "中断不得挂起写线程");
        assertTrue(runner.isInterrupted(), "persist 必须恢复中断标志");
        assertFalse(Files.exists(Path.of(dir)), "中断时不得半写");
    }

    @Test
    void persistReturnsFalseWhenDiskWriteFails() throws Exception {
        TurnLease lease = mock(TurnLease.class);
        when(gate.acquire(anyString())).thenReturn(lease);
        // 目标"目录"实为普通文件，createDirectories 必然失败 → 磁盘写失败
        Path blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "not a directory");

        boolean saved = refiner().persist("skill-a", "refined content", blocker.toString());

        assertFalse(saved, "磁盘写失败不得伪装成功（评估器读磁盘，假成功会污染演进闭环）");
        verify(lease).close();
    }

    @Test
    void lockKeyFollowsSkillNameGranularity() throws Exception {
        TurnLease lease = mock(TurnLease.class);
        when(gate.acquire(anyString())).thenReturn(lease);

        refiner().persist("orders-export", "c", tempDir.toString());

        verify(gate).acquire("muse:skill:default:orders-export");
    }
}
