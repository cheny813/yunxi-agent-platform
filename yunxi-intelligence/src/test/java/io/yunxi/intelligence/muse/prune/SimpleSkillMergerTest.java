package io.yunxi.intelligence.muse.prune;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link SimpleSkillMerger} 单元测试：TF-IDF 余弦相似度的边界与基本性质
 * （相同文本 = 1、无重合词 = 0、空/空白输入 = 0、中文二元切分对相似文本给正值）。
 */
class SimpleSkillMergerTest {

    private final SimpleSkillMerger merger = new SimpleSkillMerger();

    @Test
    void identicalTextSimilarityIsOne() {
        assertEquals(1.0d, merger.similarity("hello world", "hello world"), 1e-9);
    }

    @Test
    void disjointTextSimilarityIsZero() {
        assertEquals(0.0d, merger.similarity("hello world", "foo bar baz"), 1e-9);
    }

    @Test
    void nullOrBlankReturnsZero() {
        assertEquals(0.0d, merger.similarity(null, "x"));
        assertEquals(0.0d, merger.similarity("x", ""));
        assertEquals(0.0d, merger.similarity("  ", "  "));
    }

    @Test
    void similarChineseTextHasPositiveSimilarity() {
        // 中文按二元字切分，重叠 bigram 越多相似度越高。
        double sim = merger.similarity("订单处理流程说明", "订单处理步骤说明");
        assertTrue(sim > 0.0d, "高度重合中文文本应给出正相似度");
    }
}
