package io.yunxi.intelligence.muse.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link EvalReport} 单元测试：{@code isAllPassed} 守卫（总用例数 > 0 且零失败），
 * 以及 {@code fromRaw} 对 pytest 末行汇总 / 逐用例明细 / 零用例退化的解析。
 */
class EvalReportTest {

    @Test
    void isAllPassedRequiresPositiveTotalAndZeroFailed() {
        EvalReport report = new EvalReport();
        report.setTotal(0);
        report.setFailed(0);
        assertFalse(report.isAllPassed(), "零用例不得误判为通过");

        report.setTotal(2);
        report.setPassed(2);
        report.setFailed(0);
        assertTrue(report.isAllPassed());

        report.setFailed(1);
        assertFalse(report.isAllPassed());
    }

    @Test
    void fromRawParsesPytestSummaryLine() {
        String stdout = "PASSED tests/test_a.py::t1\nFAILED tests/test_b.py::t2\n"
                + "3 passed, 1 failed in 0.12s";
        EvalReport report = EvalReport.fromRaw("skill", stdout, "", 1);
        // 末行汇总覆盖：3 通过 1 失败，总计 4。
        assertEquals(4, report.getTotal());
        assertEquals(3, report.getPassed());
        assertEquals(1, report.getFailed());
        assertFalse(report.isAllPassed());
    }

    @Test
    void fromRawFallsBackToPerCaseWhenNoSummary() {
        String stdout = "PASSED tests/test_a.py::t1\nFAILED tests/test_b.py::t2";
        EvalReport report = EvalReport.fromRaw("skill", stdout, "", 0);
        assertEquals(2, report.getTotal());
        assertEquals(1, report.getPassed());
        assertEquals(1, report.getFailed());
        assertEquals(2, report.getCases().size());
    }

    @Test
    void fromRawExitZeroNoOutputTreatsOverallPass() {
        EvalReport report = EvalReport.fromRaw("skill", "", "", 0);
        assertTrue(report.isAllPassed(), "非零退出且零用例须判整体通过");
    }

    @Test
    void fromRawExitNonZeroNoOutputTreatsOverallFail() {
        EvalReport report = EvalReport.fromRaw("skill", "", "", 1);
        assertFalse(report.isAllPassed());
        assertEquals(1, report.getFailed());
    }

    @Test
    void fromRawHandlesNullStreams() {
        EvalReport report = EvalReport.fromRaw("skill", null, null, 0);
        assertTrue(report.isAllPassed());
    }
}
