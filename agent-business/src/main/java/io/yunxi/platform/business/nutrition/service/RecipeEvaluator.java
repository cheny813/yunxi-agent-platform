package io.yunxi.platform.business.nutrition.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 食谱营养评估器（本地内联版）
 *
 * <p>
 * 替代 MCP 远程 {@code evaluate_recipe} 工具，将纯内存计算逻辑内联到本地服务，
 * 避免 MCP 网络开销和重试机制导致的延迟（原每次调用耗时约15s）。
 * </p>
 *
 * <p>
 * 评分逻辑与 MCP 版 {@code EvaluateRecipeTool} 保持一致：
 * <ul>
 *   <li>能量供给 30%</li>
 *   <li>宏量营养素（蛋白质）25%</li>
 *   <li>脂肪 15%</li>
 *   <li>碳水化合物 15%</li>
 *   <li>食材多样性 15%</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "recipe.evaluator.local-enabled", havingValue = "true", matchIfMissing = true)
public class RecipeEvaluator {

    // ==================== 评分权重 ====================
    private static final double CALORIE_WEIGHT = 0.30;
    private static final double PROTEIN_WEIGHT = 0.25;
    private static final double FAT_WEIGHT = 0.15;
    private static final double CARBS_WEIGHT = 0.15;
    private static final double VARIETY_WEIGHT = 0.15;

    // ==================== 标准餐次占比 ====================
    private static final Map<String, Double> MEAL_RATIO = Map.of(
            "早餐", 0.30, "早点", 0.06,
            "午餐", 0.38, "午点", 0.06,
            "晚餐", 0.30, "晚点", 0.06
    );

    // ==================== 学校类型宽松度 ====================
    private static final Map<String, Double> SCHOOL_TYPE_TOLERANCE = Map.of(
            "优质城市", 1.10,
            "普通城市", 1.20,
            "农村", 1.35
    );

    private static final Map<String, Integer> SCHOOL_TYPE_TARGET = Map.of(
            "优质城市", 95,
            "普通城市", 90,
            "农村", 80
    );

    // ==================== 餐费价格区间宽松度 ====================
    private static final double[][] PRICE_TIERS = {
            {0, 5, 1.30},
            {5, 8, 1.20},
            {8, 12, 1.10},
            {12, Double.MAX_VALUE, 1.0}
    };

    /**
     * 评估食谱营养评分
     *
     * @param request 评估请求参数
     * @return 评估结果
     */
    public EvaluateResult evaluate(EvaluateRequest request) {
        double tolerance = calculateTolerance(request.getSchoolType(), request.getMealPrice());
        double totalMealRatio = calculateTotalMealRatio(request.getMealTypes());
        int dayCount = Math.max(1, request.getDayCount());

        Map<String, Object> dailyStandard = getDailyStandard(request.getCrowdType());

        double recommendedCalorieMin = ((Number) dailyStandard.get("calorieMin")).doubleValue() * dayCount * totalMealRatio;
        double recommendedCalorieMax = ((Number) dailyStandard.get("calorieMax")).doubleValue() * dayCount * totalMealRatio;
        double recommendedProteinMin = ((Number) dailyStandard.get("proteinMin")).doubleValue() * dayCount * totalMealRatio;
        double recommendedProteinMax = ((Number) dailyStandard.get("proteinMax")).doubleValue() * dayCount * totalMealRatio;
        double recommendedFatMin = ((Number) dailyStandard.get("fatMin")).doubleValue() * dayCount * totalMealRatio;
        double recommendedFatMax = ((Number) dailyStandard.get("fatMax")).doubleValue() * dayCount * totalMealRatio;
        double recommendedCarbsMin = ((Number) dailyStandard.get("carbsMin")).doubleValue() * dayCount * totalMealRatio;
        double recommendedCarbsMax = ((Number) dailyStandard.get("carbsMax")).doubleValue() * dayCount * totalMealRatio;

        // 应用宽松度
        double adjustedCalorieMin = recommendedCalorieMin / tolerance;
        double adjustedCalorieMax = recommendedCalorieMax * tolerance;
        double adjustedProteinMin = recommendedProteinMin / tolerance;
        double adjustedProteinMax = recommendedProteinMax * tolerance;
        double adjustedFatMin = recommendedFatMin / tolerance;
        double adjustedFatMax = recommendedFatMax * tolerance;
        double adjustedCarbsMin = recommendedCarbsMin / tolerance;
        double adjustedCarbsMax = recommendedCarbsMax * tolerance;

        double calorieScore = calcScore(request.getCalorie(), adjustedCalorieMin, adjustedCalorieMax);
        double proteinScore = calcScore(request.getProtein(), adjustedProteinMin, adjustedProteinMax);
        double fatScore = calcFatScore(request.getFat(), adjustedFatMin, adjustedFatMax);
        double carbsScore = calcScore(request.getCarbs(), adjustedCarbsMin, adjustedCarbsMax);

        int minDishCount = Math.max(2, request.getMealTypes().size() * dayCount);
        double varietyScore = calcVarietyScore(request.getDishCount(), minDishCount, tolerance);

        double totalScore = calorieScore * CALORIE_WEIGHT +
                proteinScore * PROTEIN_WEIGHT +
                fatScore * FAT_WEIGHT +
                carbsScore * CARBS_WEIGHT +
                varietyScore * VARIETY_WEIGHT;
        totalScore = Math.round(totalScore * 10) / 10.0;

        int targetScore = SCHOOL_TYPE_TARGET.getOrDefault(request.getSchoolType(), 80);

        return EvaluateResult.builder()
                .totalScore(totalScore)
                .grade(getGrade(totalScore))
                .targetScore(targetScore)
                .tolerance(tolerance)
                .mealRatioPercent(Math.round(totalMealRatio * 100) + "%")
                .recommendedRange(Map.of(
                        "calorie", Map.of("min", Math.round(recommendedCalorieMin), "max", Math.round(recommendedCalorieMax)),
                        "protein", Map.of("min", Math.round(recommendedProteinMin * 10) / 10.0, "max", Math.round(recommendedProteinMax * 10) / 10.0),
                        "fat", Map.of("min", Math.round(recommendedFatMin * 10) / 10.0, "max", Math.round(recommendedFatMax * 10) / 10.0),
                        "carbs", Map.of("min", Math.round(recommendedCarbsMin), "max", Math.round(recommendedCarbsMax))
                ))
                .breakdown(Map.of(
                        "calorie", Map.of("score", Math.round(calorieScore * 10) / 10.0, "value", request.getCalorie(), "weight", CALORIE_WEIGHT),
                        "protein", Map.of("score", Math.round(proteinScore * 10) / 10.0, "value", request.getProtein(), "weight", PROTEIN_WEIGHT),
                        "fat", Map.of("score", Math.round(fatScore * 10) / 10.0, "value", request.getFat(), "weight", FAT_WEIGHT),
                        "carbs", Map.of("score", Math.round(carbsScore * 10) / 10.0, "value", request.getCarbs(), "weight", CARBS_WEIGHT),
                        "variety", Map.of("score", Math.round(varietyScore * 10) / 10.0, "value", request.getDishCount(), "weight", VARIETY_WEIGHT)
                ))
                .build();
    }

    // ==================== 内部计算方法 ====================

    private double calculateTolerance(String schoolType, Double mealPrice) {
        double baseTolerance = SCHOOL_TYPE_TOLERANCE.getOrDefault(schoolType, 1.05);
        double priceTolerance = 1.0;
        if (mealPrice != null && mealPrice > 0) {
            priceTolerance = getPriceTolerance(mealPrice);
        }
        return Math.max(baseTolerance, priceTolerance);
    }

    private double getPriceTolerance(double mealPrice) {
        for (double[] tier : PRICE_TIERS) {
            if (mealPrice >= tier[0] && mealPrice < tier[1]) {
                return tier[2];
            }
        }
        return 1.0;
    }

    private double calculateTotalMealRatio(List<String> mealTypes) {
        double total = 0;
        for (String meal : mealTypes) {
            Double ratio = MEAL_RATIO.get(meal);
            total += (ratio != null) ? ratio : 0.15;
        }
        return Math.min(1.0, total);
    }

    private Map<String, Object> getDailyStandard(String crowdType) {
        String t = crowdType.toLowerCase();
        if (t.contains("学龄前") || t.contains("3-6"))
            return Map.of("calorieMin", 1000, "calorieMax", 1200, "proteinMin", 30, "proteinMax", 35, "fatMin", 33, "fatMax", 40, "carbsMin", 150, "carbsMax", 180);
        if (t.contains("小学低") || t.contains("6-9") || t.contains("1-3"))
            return Map.of("calorieMin", 1200, "calorieMax", 1500, "proteinMin", 35, "proteinMax", 45, "fatMin", 40, "fatMax", 50, "carbsMin", 180, "carbsMax", 220);
        if (t.contains("小学高") || t.contains("10-12") || t.contains("4-6"))
            return Map.of("calorieMin", 1500, "calorieMax", 1800, "proteinMin", 45, "proteinMax", 55, "fatMin", 50, "fatMax", 60, "carbsMin", 220, "carbsMax", 270);
        if (t.contains("初中") || t.contains("13-15"))
            return Map.of("calorieMin", 2000, "calorieMax", 2400, "proteinMin", 55, "proteinMax", 70, "fatMin", 60, "fatMax", 75, "carbsMin", 300, "carbsMax", 360);
        if (t.contains("高中") || t.contains("16-18"))
            return Map.of("calorieMin", 2200, "calorieMax", 2600, "proteinMin", 65, "proteinMax", 80, "fatMin", 70, "fatMax", 85, "carbsMin", 330, "carbsMax", 400);
        // 教师/默认
        return Map.of("calorieMin", 1800, "calorieMax", 2200, "proteinMin", 50, "proteinMax", 65, "fatMin", 50, "fatMax", 65, "carbsMin", 250, "carbsMax", 320);
    }

    private double calcScore(double value, double min, double max) {
        if (value >= min && value <= max) return 100;
        if (value < min) return Math.max(0, 100 - (min - value) / min * 100);
        return Math.max(0, 100 - (value - max) / max * 100);
    }

    private double calcFatScore(double value, double min, double max) {
        return calcScore(value, min * 0.8, max * 1.2);
    }

    private double calcVarietyScore(int dishCount, int minDishCount, double tolerance) {
        double adjustedMin = minDishCount / tolerance;
        if (dishCount >= adjustedMin * 2) return 100;
        if (dishCount >= adjustedMin) return 80 + (dishCount - adjustedMin) / adjustedMin * 20;
        return Math.max(0, dishCount / adjustedMin * 80);
    }

    private String getGrade(double score) {
        if (score >= 90) return "A";
        if (score >= 80) return "B";
        if (score >= 70) return "C";
        if (score >= 60) return "D";
        return "F";
    }

    // ==================== DTO 类 ====================

    /**
     * 评估请求参数
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class EvaluateRequest {
        /** 人群类型：学龄前、小学低年级、小学高年级、初中生、高中生、教师 */
        private String crowdType;
        /** 食谱总热量(kcal) */
        private double calorie;
        /** 食谱总蛋白质(g) */
        private double protein;
        /** 食谱总脂肪(g) */
        private double fat;
        /** 食谱总碳水化合物(g) */
        private double carbs;
        /** 菜品数量 */
        private int dishCount;
        /** 已配置餐次列表 */
        private List<String> mealTypes;
        /** 配置天数（默认1） */
        private int dayCount;
        /** 学校类型：优质城市/普通城市/农村 */
        private String schoolType;
        /** 每餐人均费用（元），可选 */
        private Double mealPrice;
    }

    /**
     * 评估结果
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class EvaluateResult {
        /** 总分(0-100) */
        private double totalScore;
        /** 等级(A/B/C/D/F) */
        private String grade;
        /** 目标分数 */
        private int targetScore;
        /** 宽松度 */
        private double tolerance;
        /** 餐次占比 */
        private String mealRatioPercent;
        /** 推荐摄入范围 */
        private Map<String, Object> recommendedRange;
        /** 各项得分明细 */
        private Map<String, Object> breakdown;
    }
}