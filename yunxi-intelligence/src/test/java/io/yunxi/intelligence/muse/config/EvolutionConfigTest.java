package io.yunxi.intelligence.muse.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * MUSE 配置默认值契约验证：{@link EvolutionConfig} 是 AgentScope 不覆盖的自进化闭环参数，
 * 默认开启（开箱即用，enabled=true），需显式设 false 关闭；画像剪枝阈值与既有基线一致。
 * 与 yunxi-app 的 RuntimePlaneWiringTest（默认注册 MUSE 工具注册器）保持一致。
 */
class EvolutionConfigTest {

    @Test
    void defaults_areConsistent() {
        EvolutionConfig c = new EvolutionConfig();
        assertTrue(c.isEnabled(), "MUSE 默认开启（开箱即用），需显式设 false 关闭");
        assertEquals(0.85, c.getPruner().getSimilarityThreshold());
        assertEquals(1, c.getPruner().getMinUsage());
        assertEquals(200, c.getPruner().getMaxSkills());

        EvolutionConfig.ProfileAware pa = c.getPruner().getProfileAware();
        assertTrue(pa.isEnabled(), "画像判据默认开启");
        assertEquals(3, pa.getGeneralUsageThreshold(), "通用价值广度阈值");
        assertEquals(5, pa.getOverfitUsageThreshold(), "过拟合高频阈值");
    }
}
