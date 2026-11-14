package io.yunxi.intelligence.muse.prune;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * MUSE 使用画像聚合产物验证（基线重建）：{@link UsageProfile} 是剪枝器「翻案」判据的只读输入，
 * 字段契约（去重用户数 / 分类分布 / 成功次数）必须稳定。
 */
class UsageProfileTest {

    @Test
    void gettersSetters_holdAggregatedFields() {
        UsageProfile up = new UsageProfile();
        up.setDistinctUsers(3);
        up.setSuccessCalls(7);
        up.setCategoryDistribution(Map.of("search", 4, "write", 3));

        assertEquals(3, up.getDistinctUsers());
        assertEquals(7, up.getSuccessCalls());
        assertEquals(Map.of("search", 4, "write", 3), up.getCategoryDistribution());
    }
}
