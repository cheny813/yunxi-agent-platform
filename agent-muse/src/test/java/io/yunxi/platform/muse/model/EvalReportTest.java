package io.yunxi.platform.muse.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * EvalReport 输出解析单元测试。
 */
class EvalReportTest {

    @Test
    void testJavaTestOutputAllPassed() {
        // 模拟 SkillStructureTest 的通过输出
        String stdout = "OK: 技能结构校验通过\n";
        EvalReport report = EvalReport.fromRaw("data_stats", stdout, "", 0);
        assertTrue(report.isAllPassed());
        assertEquals(1, report.getTotal());
        assertEquals(1, report.getPassed());
        assertEquals(0, report.getFailed());
        assertEquals(1.0, report.getPassRate(), 0.001);
    }

    @Test
    void testJavaTestOutputFailed() {
        // 模拟 Java 测试失败（非零退出码）
        String stdout = "FAIL: 缺少关键词: 均值\n";
        EvalReport report = EvalReport.fromRaw("bad_skill", stdout, "", 1);
        assertFalse(report.isAllPassed());
        assertEquals(1, report.getTotal());
        assertEquals(0, report.getPassed());
        assertEquals(1, report.getFailed());
        assertEquals(0.0, report.getPassRate(), 0.001);
    }

    @Test
    void testPytestStyleOutput() {
        String stdout = "tests/test_data_stats.py::test_basic PASSED\ntests/test_data_stats.py::test_edge PASSED\ntests/test_data_stats.py::test_missing FAILED\n\n3 passed, 1 failed in 0.12s\n";
        EvalReport report = EvalReport.fromRaw("data_stats", stdout, "", 1);
        assertEquals(4, report.getTotal());
        assertEquals(3, report.getPassed());
        assertEquals(1, report.getFailed());
        assertEquals(3.0 / 4.0, report.getPassRate(), 0.001);
    }

    @Test
    void testEmptyOutputExitZero() {
        EvalReport report = EvalReport.fromRaw("empty", "", "", 0);
        assertTrue(report.isAllPassed());
        assertEquals(1, report.getTotal());
        assertEquals(1.0, report.getPassRate(), 0.001);
    }

    @Test
    void testEmptyOutputExitNonZero() {
        EvalReport report = EvalReport.fromRaw("empty", "", "error", 1);
        assertFalse(report.isAllPassed());
        assertEquals(1, report.getTotal());
        assertEquals(0, report.getPassed());
        assertEquals(1, report.getFailed());
    }

    @Test
    void testStderrSummary() {
        EvalReport report = EvalReport.fromRaw("broken", "some output", "compilation error: cannot find symbol", 1);
        String summary = report.getSummary();
        assertTrue(summary.contains("失败摘要"));
        assertTrue(summary.contains("compilation error"));
    }
}
