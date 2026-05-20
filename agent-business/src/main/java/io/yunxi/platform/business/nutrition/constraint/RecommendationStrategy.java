package io.yunxi.platform.business.nutrition.constraint;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 配餐推荐策略 DTO
 *
 * <p>
 * 整合所有约束维度（民族、菜系、季节、价格、天气）为统一的推荐策略，
 * 供 Agent 上下文注入或主动查询使用。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationStrategy {

    /** 价格模式: FIXED(集中采购) / FLUCTUATION(市场波动) */
    private String priceMode;

    /** 天气对价格的影响因子 (1.0=无影响, >1.0=涨价) */
    private BigDecimal weatherImpactFactor;

    /** 推荐菜系列表 (如: 川菜, 粤菜) */
    private List<String> preferredCuisines;

    /** 受限食材列表 (如: 猪肉, 猪油 — 因民族约束) */
    private List<String> restrictedIngredients;

    /** 当季食材列表 */
    private List<String> seasonalIngredients;

    /** 反季食材列表 (可替换) */
    private List<String> offSeasonIngredients;

    /** 策略中文描述 (注入Agent上下文) */
    private String strategyDescription;
}
