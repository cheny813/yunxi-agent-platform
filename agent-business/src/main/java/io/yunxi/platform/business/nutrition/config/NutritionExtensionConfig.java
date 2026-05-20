package io.yunxi.platform.business.nutrition.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 营养扩展配置属性类
 *
 * <p>
 * 控制营养数据同步扩展功能的开关和参数，所有扩展默认关闭，不影响现有学校配餐流程。
 * </p>
 *
 * <h3>配置示例 (application-business.yml)</h3>
 *
 * <pre>
 * nutrition-extension:
 *   regional-cuisine-sync:
 *     enabled: false
 *   ingredient-seasonality-sync:
 *     enabled: false
 *   school-ethnic-sync:
 *     enabled: false
 *   weather:
 *     enabled: false
 *     cache-ttl-hours: 1
 *   price:
 *     enabled: false
 *     cache-ttl-hours: 2
 *   price-prediction:
 *     enabled: false
 *   constraint:
 *     enabled: false
 *     default-mode: SCHOOL
 * </pre>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "nutrition-extension")
public class NutritionExtensionConfig {

    /** 区域菜系字典同步配置 */
    private RegionalCuisineSyncConfig regionalCuisineSync = new RegionalCuisineSyncConfig();

    /** 食材季节性属性同步配置 */
    private IngredientSeasonalitySyncConfig ingredientSeasonalitySync = new IngredientSeasonalitySyncConfig();

    /** 学校民族配置同步配置 */
    private SchoolEthnicSyncConfig schoolEthnicSync = new SchoolEthnicSyncConfig();

    /** 天气数据服务配置 */
    private WeatherConfig weather = new WeatherConfig();

    /** 价格数据服务配置 */
    private PriceConfig price = new PriceConfig();

    /** 价格预测服务配置 */
    private PricePredictionConfig pricePrediction = new PricePredictionConfig();

    /** 配餐约束服务配置 */
    private ConstraintConfig constraint = new ConstraintConfig();

    /**
     * 区域菜系字典同步配置
     */
    @Data
    public static class RegionalCuisineSyncConfig {
        /** 是否启用区域菜系字典同步到 Milvus */
        private boolean enabled = false;
    }

    /**
     * 食材季节性属性同步配置
     */
    @Data
    public static class IngredientSeasonalitySyncConfig {
        /** 是否启用食材季节性属性同步到 Milvus */
        private boolean enabled = false;
    }

    /**
     * 学校民族配置同步配置
     */
    @Data
    public static class SchoolEthnicSyncConfig {
        /** 是否启用学校民族配置同步到 Milvus */
        private boolean enabled = false;
    }

    /**
     * 天气数据服务配置
     */
    @Data
    public static class WeatherConfig {
        /** 是否启用天气数据服务 */
        private boolean enabled = false;
        /** 当日天气缓存时间（小时） */
        private int cacheTtlHours = 1;
        /** 天气预报缓存时间（小时） */
        private int forecastCacheTtlHours = 6;
        /** 天气API提供商: qweather(和风天气,默认) / custom(自定义URL) */
        private String provider = "qweather";
        /** 天气API服务地址（可选，custom模式时使用） */
        private String apiUrl;
        /** 天气API密钥（和风天气需要key） */
        private String apiKey;
    }

    /**
     * 价格数据服务配置
     */
    @Data
    public static class PriceConfig {
        /** 是否启用价格数据服务 */
        private boolean enabled = false;
        /** 价格缓存时间（小时） */
        private int cacheTtlHours = 2;
        /** 价格数据同步 cron 表达式 */
        private String syncCron = "0 0 3 * * ?";
        /** 价格API服务地址（可选，对接外部价格API） */
        private String apiUrl;
        /** 价格API密钥（可选） */
        private String apiKey;
    }

    /**
     * 价格预测服务配置
     */
    @Data
    public static class PricePredictionConfig {
        /** 是否启用价格预测服务 */
        private boolean enabled = false;
        /** 历史数据回看天数 */
        private int historyDays = 30;
        /** 默认预测天数 */
        private int defaultPredictDays = 7;
    }

    /**
     * 配餐约束服务配置
     */
    @Data
    public static class ConstraintConfig {
        /** 是否启用动态配餐约束服务 */
        private boolean enabled = false;
        /** 默认模式: SCHOOL(学校端) / PERSONAL(个人端,预留) */
        private String defaultMode = "SCHOOL";
    }
}
