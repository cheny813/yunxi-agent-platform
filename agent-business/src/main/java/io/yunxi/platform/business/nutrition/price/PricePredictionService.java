package io.yunxi.platform.business.nutrition.price;

import io.yunxi.platform.business.nutrition.config.NutritionExtensionConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 价格预测服务
 *
 * <p>
 * 基于历史价格数据使用移动平均算法进行价格预测，计算95%置信区间。
 * FIXED 模式直接返回固定价格，不做预测。
 * </p>
 *
 * <p>
 * 通过 {@code nutrition-extension.price-prediction.enabled=true} 启用，
 * 默认关闭，不影响现有学校配餐流程。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "nutrition-extension.price-prediction.enabled", havingValue = "true")
public class PricePredictionService {

    /** 数据库访问模板 */
    private final JdbcTemplate jdbcTemplate;
    /** 价格数据服务 */
    private final PriceDataService priceDataService;
    /** 营养扩展配置 */
    private final NutritionExtensionConfig config;

    /**
     * 构造函数
     *
     * @param jdbcTemplate    数据库访问
     * @param priceDataService 价格数据服务
     * @param config          扩展配置
     */
    public PricePredictionService(JdbcTemplate jdbcTemplate,
                                  PriceDataService priceDataService,
                                  NutritionExtensionConfig config) {
        this.jdbcTemplate = jdbcTemplate;
        this.priceDataService = priceDataService;
        this.config = config;
    }

    /**
     * 预测食材未来N天的价格
     *
     * <p>
     * FIXED 模式直接返回固定价格。
     * FLUCTUATION 模式使用移动平均算法预测，并计算95%置信区间。
     * </p>
     *
     * @param districtCode 区县编码
     * @param ingredientId 食材ID
     * @param days         预测天数
     * @return 预测价格，无数据时返回 null
     */
    public PredictionResult predictPrice(String districtCode, Long ingredientId, int days) {
        // FIXED 模式不做预测
        PriceMode mode = priceDataService.getPriceMode(districtCode);
        if (mode == PriceMode.FIXED) {
            BigDecimal fixedPrice = priceDataService.getCurrentPrice(districtCode, ingredientId);
            if (fixedPrice != null) {
                return PredictionResult.builder()
                        .predictedPrice(fixedPrice)
                        .confidenceLower(fixedPrice)
                        .confidenceUpper(fixedPrice)
                        .days(days)
                        .mode(PriceMode.FIXED)
                        .build();
            }
            return null;
        }

        // FLUCTUATION 模式：加载历史数据
        int historyDays = config.getPricePrediction().getHistoryDays();
        List<BigDecimal> historicalPrices = loadHistoricalPrices(districtCode, ingredientId, historyDays);

        if (historicalPrices.size() < 3) {
            // 历史数据不足，使用当前价格
            BigDecimal currentPrice = priceDataService.getCurrentPrice(districtCode, ingredientId);
            if (currentPrice == null) {
                return null;
            }
            return PredictionResult.builder()
                    .predictedPrice(currentPrice)
                    .confidenceLower(currentPrice.multiply(new BigDecimal("0.9")))
                    .confidenceUpper(currentPrice.multiply(new BigDecimal("1.1")))
                    .days(days)
                    .mode(PriceMode.FLUCTUATION)
                    .build();
        }

        // 移动平均预测
        BigDecimal predictedPrice = calculateMovingAverage(historicalPrices);

        // 标准差
        BigDecimal stdDev = calculateStandardDeviation(historicalPrices, predictedPrice);

        // 95% 置信区间 = 均值 ± 1.96 * 标准差
        BigDecimal margin = stdDev.multiply(new BigDecimal("1.96"));
        BigDecimal lower = predictedPrice.subtract(margin).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal upper = predictedPrice.add(margin).setScale(2, RoundingMode.HALF_UP);

        // 保存预测结果
        savePrediction(ingredientId, districtCode, predictedPrice, lower, upper, days);

        return PredictionResult.builder()
                .predictedPrice(predictedPrice)
                .confidenceLower(lower)
                .confidenceUpper(upper)
                .days(days)
                .mode(PriceMode.FLUCTUATION)
                .build();
    }

    // ==================== 内部方法 ====================

    /**
     * 加载历史价格数据
     *
     * @param districtCode 区县编码
     * @param ingredientId 食材ID
     * @param historyDays  历史回看天数
     * @return 历史价格列表，按日期升序排列
     */
    private List<BigDecimal> loadHistoricalPrices(String districtCode, Long ingredientId, int historyDays) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(historyDays);

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT price FROM market_price_cache " +
                            "WHERE ingredient_id = ? AND district_code = ? AND price_date BETWEEN ? AND ? " +
                            "ORDER BY price_date",
                    ingredientId, districtCode, startDate, endDate);

            return rows.stream()
                    .map(row -> new BigDecimal(row.get("price").toString()))
                    .toList();
        } catch (Exception e) {
            log.warn("加载历史价格数据失败: districtCode={}, ingredientId={}", districtCode, ingredientId, e);
            return List.of();
        }
    }

    /**
     * 计算移动平均
     *
     * @param prices 价格列表
     * @return 移动平均值，保留2位小数
     */
    private BigDecimal calculateMovingAverage(List<BigDecimal> prices) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal price : prices) {
            sum = sum.add(price);
        }
        return sum.divide(new BigDecimal(prices.size()), 2, RoundingMode.HALF_UP);
    }

    /**
     * 计算标准差
     *
     * @param prices 价格列表
     * @param mean   均值
     * @return 标准差，保留2位小数
     */
    private BigDecimal calculateStandardDeviation(List<BigDecimal> prices, BigDecimal mean) {
        BigDecimal sumSquares = BigDecimal.ZERO;
        for (BigDecimal price : prices) {
            BigDecimal diff = price.subtract(mean);
            sumSquares = sumSquares.add(diff.multiply(diff));
        }
        BigDecimal variance = sumSquares.divide(new BigDecimal(prices.size()), 4, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue())).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 保存预测结果到数据库
     *
     * @param ingredientId   食材ID
     * @param districtCode   区县编码
     * @param predictedPrice 预测价格
     * @param lower          置信区间下限
     * @param upper          置信区间上限
     * @param days           预测天数
     */
    private void savePrediction(Long ingredientId, String districtCode,
                                BigDecimal predictedPrice, BigDecimal lower, BigDecimal upper, int days) {
        try {
            LocalDate targetDate = LocalDate.now().plusDays(days);
            jdbcTemplate.update(
                    "INSERT INTO price_prediction_history " +
                            "(ingredient_id, district_code, predicted_price, confidence_lower, confidence_upper, prediction_date) " +
                            "VALUES (?, ?, ?, ?, ?, ?)",
                    ingredientId, districtCode, predictedPrice, lower, upper, targetDate);
        } catch (Exception e) {
            log.warn("保存价格预测结果失败: ingredientId={}, districtCode={}", ingredientId, districtCode, e);
        }
    }

    // ==================== 预测结果DTO ====================

    /**
     * 价格预测结果
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PredictionResult {
        /** 预测价格(元/kg) */
        private BigDecimal predictedPrice;
        /** 95%置信区间下限 */
        private BigDecimal confidenceLower;
        /** 95%置信区间上限 */
        private BigDecimal confidenceUpper;
        /** 预测天数 */
        private int days;
        /** 价格模式 */
        private PriceMode mode;
    }
}
