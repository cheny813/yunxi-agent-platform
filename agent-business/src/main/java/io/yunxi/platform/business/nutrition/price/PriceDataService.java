package io.yunxi.platform.business.nutrition.price;

import io.yunxi.platform.business.nutrition.config.NutritionExtensionConfig;
import io.yunxi.platform.business.nutrition.weather.WeatherDataService;
import io.yunxi.platform.business.nutrition.weather.WeatherType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 价格数据服务
 *
 * <p>
 * 提供食材价格查询功能，支持 FIXED(集中采购) 和 FLUCTUATION(市场波动) 两种价格模式。
 * FIXED 模式使用配置的固定价格，FLUCTUATION 模式使用市场价格并可叠加天气影响因子。
 * </p>
 *
 * <p>
 * 通过 {@code nutrition-extension.price.enabled=true} 启用，
 * 默认关闭，不影响现有学校配餐流程。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "nutrition-extension.price.enabled", havingValue = "true")
public class PriceDataService {

    /** 数据库访问模板 */
    private final JdbcTemplate jdbcTemplate;
    /** 天气数据服务（可选，用于天气影响因子计算） */
    private final WeatherDataService weatherDataService;
    /** 营养扩展配置 */
    private final NutritionExtensionConfig config;

    /**
     * 构造函数
     *
     * @param jdbcTemplate       数据库访问
     * @param weatherDataService 天气数据服务（可选）
     * @param config             扩展配置
     */
    public PriceDataService(JdbcTemplate jdbcTemplate,
            WeatherDataService weatherDataService,
            NutritionExtensionConfig config) {
        this.jdbcTemplate = jdbcTemplate;
        this.weatherDataService = weatherDataService;
        this.config = config;
    }

    /**
     * 获取当前价格
     *
     * <p>
     * FIXED 模式从 district_price_mode 表的 fixed_price_config 读取；
     * FLUCTUATION 模式从 market_price_cache 表读取最新市场价格。
     * </p>
     *
     * @param districtCode 区县编码
     * @param ingredientId 食材ID
     * @return 当前价格(元/kg)，未找到返回 null
     */
    public BigDecimal getCurrentPrice(String districtCode, Long ingredientId) {
        PriceMode mode = getPriceMode(districtCode);

        if (mode == PriceMode.FIXED) {
            return getFixedPrice(districtCode, ingredientId);
        } else {
            return getMarketPrice(districtCode, ingredientId);
        }
    }

    /**
     * 获取区县价格模式
     *
     * @param districtCode 区县编码
     * @return 价格模式，默认 FLUCTUATION
     */
    public PriceMode getPriceMode(String districtCode) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT price_mode FROM district_price_mode WHERE district_code = ?",
                    districtCode);

            if (!rows.isEmpty()) {
                String modeStr = (String) rows.get(0).get("price_mode");
                return PriceMode.valueOf(modeStr);
            }
        } catch (Exception e) {
            log.warn("查询区县价格模式失败: districtCode={}", districtCode, e);
        }
        return PriceMode.FLUCTUATION;
    }

    /**
     * 获取天气调整后价格
     *
     * <p>
     * 仅 FLUCTUATION 模式生效，市场价格 × 天气影响因子。
     * FIXED 模式直接返回固定价格，不受天气影响。
     * </p>
     *
     * @param districtCode 区县编码
     * @param ingredientId 食材ID
     * @param weatherType  天气类型
     * @return 调整后价格
     */
    public BigDecimal getAdjustedPrice(String districtCode, Long ingredientId, WeatherType weatherType) {
        BigDecimal basePrice = getCurrentPrice(districtCode, ingredientId);
        if (basePrice == null) {
            return null;
        }

        PriceMode mode = getPriceMode(districtCode);
        if (mode == PriceMode.FIXED) {
            return basePrice;
        }

        // FLUCTUATION 模式叠加天气影响
        BigDecimal impactFactor = weatherDataService.calculatePriceImpactFactor(weatherType);
        return basePrice.multiply(impactFactor).setScale(2, RoundingMode.HALF_UP);
    }

    // ==================== 内部方法 ====================

    /**
     * 从固定价格配置获取价格
     *
     * @param districtCode 区县编码
     * @param ingredientId 食材ID
     * @return 固定价格(元/kg)，未找到返回 null
     */
    private BigDecimal getFixedPrice(String districtCode, Long ingredientId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT fixed_price_config FROM district_price_mode WHERE district_code = ?",
                    districtCode);

            if (!rows.isEmpty()) {
                String configJson = (String) rows.get(0).get("fixed_price_config");
                if (configJson != null) {
                    // fixed_price_config 格式: {"123": 5.50, "456": 8.00}
                    Map<String, Object> priceMap = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(configJson, Map.class);
                    Object price = priceMap.get(ingredientId.toString());
                    if (price != null) {
                        return new BigDecimal(price.toString());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("查询固定价格失败: districtCode={}, ingredientId={}", districtCode, ingredientId, e);
        }
        return null;
    }

    /**
     * 从市场价格缓存获取最新价格
     *
     * @param districtCode 区县编码
     * @param ingredientId 食材ID
     * @return 市场价格(元/kg)，未找到返回 null
     */
    private BigDecimal getMarketPrice(String districtCode, Long ingredientId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT price FROM market_price_cache " +
                            "WHERE ingredient_id = ? AND district_code = ? " +
                            "ORDER BY price_date DESC LIMIT 1",
                    ingredientId, districtCode);

            if (!rows.isEmpty()) {
                return new BigDecimal(rows.get(0).get("price").toString());
            }
        } catch (Exception e) {
            log.warn("查询市场价格失败: districtCode={}, ingredientId={}", districtCode, ingredientId, e);
        }
        return null;
    }
}
