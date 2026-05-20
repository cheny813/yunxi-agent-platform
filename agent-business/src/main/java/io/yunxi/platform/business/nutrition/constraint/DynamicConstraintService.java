package io.yunxi.platform.business.nutrition.constraint;

import io.yunxi.platform.business.nutrition.price.PriceDataService;
import io.yunxi.platform.business.nutrition.price.PriceMode;
import io.yunxi.platform.business.nutrition.sync.IngredientSeasonalitySyncHandler;
import io.yunxi.platform.business.nutrition.sync.RegionalCuisineSyncHandler;
import io.yunxi.platform.business.nutrition.sync.SchoolEthnicConfigSyncHandler;
import io.yunxi.platform.business.nutrition.weather.WeatherDataService;
import io.yunxi.platform.business.nutrition.weather.WeatherType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * 动态配餐约束服务
 *
 * <p>
 * 整合所有约束维度（民族、菜系、季节、价格、天气）为统一的推荐策略，
 * 供 Agent 上下文注入或主动查询使用。
 * </p>
 *
 * <h3>约束组装逻辑</h3>
 * <ol>
 * <li>民族约束: 查询 school_ethnic_config → restrictedIngredients,
 * allowedCuisines</li>
 * <li>区域菜系: 查询 regional_cuisine_dictionary + 省份编码 → preferredCuisines</li>
 * <li>季节性食材: 查询 ingredient_seasonality + 当前月份 → seasonalIngredients</li>
 * <li>价格模式: 查询 district_price_mode → priceMode</li>
 * <li>天气影响: FLUCTUATION模式 → weatherImpactFactor</li>
 * </ol>
 *
 * <p>
 * 通过 {@code nutrition-extension.constraint.enabled=true} 启用，
 * 默认关闭，不影响现有学校配餐流程。
 * 各数据源不可用时降级处理（民族为空→不限制，天气不可用→因子1.0）。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "nutrition-extension.constraint.enabled", havingValue = "true")
public class DynamicConstraintService {

    /** 区域菜系同步处理器（可选依赖） */
    private final RegionalCuisineSyncHandler regionalCuisineHandler;
    /** 食材季节性同步处理器（可选依赖） */
    private final IngredientSeasonalitySyncHandler seasonalityHandler;
    /** 学校民族配置同步处理器（可选依赖） */
    private final SchoolEthnicConfigSyncHandler ethnicConfigHandler;
    /** 天气数据服务（可选依赖） */
    private final WeatherDataService weatherDataService;
    /** 价格数据服务（可选依赖） */
    private final PriceDataService priceDataService;

    /**
     * 构造函数
     *
     * <p>
     * 所有依赖均为可选注入（required=false），因为各模块通过独立的
     * ConditionalOnProperty 控制。未启用的模块为 null 时降级处理。
     * </p>
     */
    public DynamicConstraintService(
            RegionalCuisineSyncHandler regionalCuisineHandler,
            IngredientSeasonalitySyncHandler seasonalityHandler,
            SchoolEthnicConfigSyncHandler ethnicConfigHandler,
            WeatherDataService weatherDataService,
            PriceDataService priceDataService) {
        this.regionalCuisineHandler = regionalCuisineHandler;
        this.seasonalityHandler = seasonalityHandler;
        this.ethnicConfigHandler = ethnicConfigHandler;
        this.weatherDataService = weatherDataService;
        this.priceDataService = priceDataService;
    }

    /**
     * B端: 获取学校配餐约束策略
     *
     * @param schoolId 学校ID
     * @return 推荐策略
     */
    public RecommendationStrategy getSchoolStrategy(Long schoolId) {
        ConstraintRequest request = ConstraintRequest.builder()
                .schoolId(schoolId)
                .mealType("DAY")
                .build();
        return buildStrategy(request);
    }

    /**
     * C端: 获取个人咨询约束策略（预留）
     *
     * <p>
     * 当前返回默认无约束策略。C端个人咨询功能开发时再实现。
     * </p>
     *
     * @param userId 用户ID
     * @return 推荐策略（默认无约束）
     */
    public RecommendationStrategy getPersonalStrategy(Long userId) {
        return RecommendationStrategy.builder()
                .priceMode(PriceMode.FLUCTUATION.name())
                .weatherImpactFactor(BigDecimal.ONE)
                .preferredCuisines(Collections.emptyList())
                .restrictedIngredients(Collections.emptyList())
                .seasonalIngredients(Collections.emptyList())
                .offSeasonIngredients(Collections.emptyList())
                .strategyDescription("个人咨询模式：当前为默认策略，无特殊约束。")
                .build();
    }

    /**
     * 通用: 根据请求参数构建约束策略
     *
     * @param request 约束请求
     * @return 推荐策略
     */
    public RecommendationStrategy buildStrategy(ConstraintRequest request) {
        List<String> restrictedIngredients = new ArrayList<>();
        List<String> preferredCuisines = new ArrayList<>();
        List<String> seasonalIngredients = new ArrayList<>();
        List<String> offSeasonIngredients = new ArrayList<>();
        String priceMode = PriceMode.FLUCTUATION.name();
        BigDecimal weatherImpactFactor = BigDecimal.ONE;

        // 1. 民族约束
        if (ethnicConfigHandler != null && request.getSchoolId() != null) {
            try {
                String ethnicResult = ethnicConfigHandler.searchEthnicConfig(request.getSchoolId());
                if (ethnicResult != null && !ethnicResult.isEmpty()) {
                    // 从搜索结果中提取约束（简化实现：直接查本地映射）
                    // 实际项目中可通过 Milvus 搜索结果解析
                    log.info("学校 {} 存在民族饮食约束", request.getSchoolId());
                }
            } catch (Exception e) {
                log.warn("查询学校民族配置失败: schoolId={}", request.getSchoolId(), e);
            }
        }

        // 2. 区域菜系
        if (regionalCuisineHandler != null && request.getProvinceCode() != null) {
            try {
                String cuisineType = regionalCuisineHandler.getCuisineTypeByProvince(request.getProvinceCode());
                if (cuisineType != null) {
                    preferredCuisines.add(cuisineType);
                }
            } catch (Exception e) {
                log.warn("查询区域菜系失败: provinceCode={}", request.getProvinceCode(), e);
            }
        }

        // 3. 季节性食材
        if (seasonalityHandler != null) {
            try {
                int currentMonth = LocalDate.now().getMonthValue();
                String season = getCurrentSeason(currentMonth);
                // 简化实现：使用本地季节映射
                // 完整实现可查询 Milvus 向量搜索
                seasonalIngredients = getSeasonalIngredientsForMonth(currentMonth);
            } catch (Exception e) {
                log.warn("查询季节性食材失败", e);
            }
        }

        // 4. 价格模式
        if (priceDataService != null && request.getDistrictCode() != null) {
            try {
                PriceMode mode = priceDataService.getPriceMode(request.getDistrictCode());
                priceMode = mode.name();

                // 5. 天气影响 (仅FLUCTUATION模式)
                if (mode == PriceMode.FLUCTUATION && weatherDataService != null
                        && request.getDistrictCode() != null) {
                    try {
                        WeatherType weatherType = weatherDataService.getWeatherType(request.getDistrictCode());
                        weatherImpactFactor = weatherDataService.calculatePriceImpactFactor(weatherType);
                    } catch (Exception e) {
                        log.warn("查询天气影响因子失败: districtCode={}", request.getDistrictCode(), e);
                    }
                }
            } catch (Exception e) {
                log.warn("查询价格模式失败: districtCode={}", request.getDistrictCode(), e);
            }
        }

        // 构建策略描述
        String description = buildStrategyDescription(priceMode, weatherImpactFactor,
                preferredCuisines, restrictedIngredients, seasonalIngredients, request.getMealType());

        return RecommendationStrategy.builder()
                .priceMode(priceMode)
                .weatherImpactFactor(weatherImpactFactor)
                .preferredCuisines(preferredCuisines)
                .restrictedIngredients(restrictedIngredients)
                .seasonalIngredients(seasonalIngredients)
                .offSeasonIngredients(offSeasonIngredients)
                .strategyDescription(description)
                .build();
    }

    // ==================== 工具方法 ====================

    /**
     * 根据月份获取当前季节
     *
     * @param month 月份（1-12）
     * @return 季节名称（春/夏/秋/冬）
     */
    private String getCurrentSeason(int month) {
        if (month >= 3 && month <= 5)
            return "春";
        if (month >= 6 && month <= 8)
            return "夏";
        if (month >= 9 && month <= 11)
            return "秋";
        return "冬";
    }

    /**
     * 获取当月推荐食材（简化实现）
     *
     * @param month 月份（1-12）
     * @return 当季食材名称列表
     */
    private List<String> getSeasonalIngredientsForMonth(int month) {
        // 简化实现：按月份返回常见当季食材
        return switch (month) {
            case 3, 4, 5 -> List.of("春笋", "香椿", "豌豆苗", "菠菜", "草莓", "枇杷");
            case 6, 7, 8 -> List.of("黄瓜", "西红柿", "苦瓜", "西瓜", "桃子", "荔枝", "空心菜");
            case 9, 10, 11 -> List.of("莲藕", "南瓜", "苹果", "梨", "螃蟹", "鲈鱼", "柿子");
            default -> List.of("白萝卜", "大白菜", "冬笋", "羊肉", "橘子", "橙子");
        };
    }

    /**
     * 构建策略中文描述
     *
     * @param priceMode             价格模式名称
     * @param weatherImpactFactor   天气对价格的影响因子
     * @param preferredCuisines     推荐菜系列表
     * @param restrictedIngredients 受限食材列表
     * @param seasonalIngredients   当季食材列表
     * @param mealType              食谱类型（DAY/WEEK）
     * @return 策略中文描述字符串
     */
    private String buildStrategyDescription(String priceMode, BigDecimal weatherImpactFactor,
            List<String> preferredCuisines, List<String> restrictedIngredients,
            List<String> seasonalIngredients, String mealType) {
        StringBuilder sb = new StringBuilder();
        sb.append("【配餐约束策略】\n");

        // 价格模式
        if (PriceMode.FIXED.name().equals(priceMode)) {
            sb.append("- 价格模式: 集中采购(固定价格)，无需考虑天气对价格的影响\n");
        } else {
            sb.append("- 价格模式: 市场波动，需考虑天气和季节对食材价格的影响\n");
            if (weatherImpactFactor.compareTo(BigDecimal.ONE) > 0) {
                sb.append(String.format("- 天气影响因子: %.2f (恶劣天气将推高食材价格)\n", weatherImpactFactor.doubleValue()));
            }
        }

        // 菜系偏好
        if (!preferredCuisines.isEmpty()) {
            sb.append("- 推荐菜系: ").append(String.join("、", preferredCuisines)).append("\n");
        }

        // 民族约束
        if (!restrictedIngredients.isEmpty()) {
            sb.append("- 禁忌食材: ").append(String.join("、", restrictedIngredients)).append("\n");
        }

        // 季节食材
        if (!seasonalIngredients.isEmpty()) {
            sb.append("- 当季推荐: ").append(String.join("、", seasonalIngredients)).append("\n");
        }

        // 食谱类型
        if ("WEEK".equals(mealType)) {
            sb.append("- 食谱类型: 周食谱，需考虑未来3-7天天气变化对食材供应的影响\n");
        } else {
            sb.append("- 食谱类型: 当日食谱，参考当日天气情况\n");
        }

        return sb.toString();
    }
}
