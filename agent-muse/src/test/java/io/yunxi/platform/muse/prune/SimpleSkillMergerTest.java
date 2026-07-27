package io.yunxi.platform.muse.prune;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 技能相似度计算单元测试。
 */
class SimpleSkillMergerTest {

    private final SimpleSkillMerger merger = new SimpleSkillMerger();

    @Test
    void testIdenticalContent() {
        String a = "数据分析技能：提供均值、标准差、分位数等统计指标";
        String b = "数据分析技能：提供均值、标准差、分位数等统计指标";
        double sim = merger.similarity(a, b);
        assertEquals(1.0, sim, 0.001);
    }

    @Test
    void testCompletelyDifferent() {
        String a = "数据分析技能：提供均值、标准差、分位数等统计指标";
        String b = "文本清洗技能：去除空白字符和特殊符号";
        double sim = merger.similarity(a, b);
        assertTrue(sim < 0.5, "完全不同的技能相似度应 < 0.5，实际 " + sim);
    }

    @Test
    void testPartiallyOverlapping() {
        String a = "数据分析技能：提供均值、标准差、分位数";
        String b = "高级数据分析：提供均值、标准差、分位数、偏度、峰度";
        double sim = merger.similarity(a, b);
        assertTrue(sim > 0.5, "部分重叠的技能相似度应 > 0.5，实际 " + sim);
        assertTrue(sim < 1.0, "不完全相同应 < 1.0");
    }

    @Test
    void testNullOrBlank() {
        assertEquals(0.0, merger.similarity(null, "data"), 0.001);
        assertEquals(0.0, merger.similarity("data", null), 0.001);
        assertEquals(0.0, merger.similarity("", "data"), 0.001);
        assertEquals(0.0, merger.similarity("   ", ""), 0.001);
    }

    @Test
    void testEnglishContent() {
        String a = "A skill that provides data statistics including mean, standard deviation, and quantiles";
        String b = "A skill that provides data statistics including mean, standard deviation, and quantiles";
        double sim = merger.similarity(a, b);
        assertEquals(1.0, sim, 0.001);
    }

    @Test
    void testEnglishVsChineseNoOverlap() {
        String a = "数据分析职业技能说明";
        String b = "text cleaning skill for removing whitespace";
        double sim = merger.similarity(a, b);
        assertEquals(0.0, sim, 0.001);
    }

    @Test
    void testSymmetry() {
        String a = "数据分析技能：提供统计指标计算";
        String b = "文本清洗：去除空白和特殊字符";
        assertEquals(merger.similarity(a, b), merger.similarity(b, a), 0.001);
    }
}
