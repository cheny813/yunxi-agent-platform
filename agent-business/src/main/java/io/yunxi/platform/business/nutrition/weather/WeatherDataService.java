package io.yunxi.platform.business.nutrition.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.yunxi.platform.business.nutrition.config.NutritionExtensionConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 天气数据服务
 *
 * <p>
 * 提供天气数据查询和缓存功能，为配餐约束提供天气对价格的影响因子。
 * 数据优先从 MySQL 缓存表读取，过期则调用外部天气 API 刷新。
 * </p>
 *
 * <p>
 * 支持的天气API提供商：
 * <ul>
 * <li>qweather — 和风天气（默认），需要 api-key</li>
 * <li>custom — 自定义API地址，通过 api-url 配置</li>
 * </ul>
 * </p>
 *
 * <p>
 * 通过 {@code nutrition-extension.weather.enabled=true} 启用，
 * 默认关闭，不影响现有学校配餐流程。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.1.0
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "nutrition-extension.weather.enabled", havingValue = "true")
public class WeatherDataService {

    /** 数据库访问模板 */
    private final JdbcTemplate jdbcTemplate;
    /** 营养扩展配置 */
    private final NutritionExtensionConfig config;
    /** HTTP 请求客户端，用于调用外部天气 API */
    private final RestTemplate restTemplate;
    /** JSON 序列化/反序列化工具 */
    private final ObjectMapper objectMapper;

    /** 和风天气实时天气 API */
    private static final String QWEATHER_NOW_URL = "https://devapi.qweather.com/v7/weather/now";
    /** 和风天气预报 API（3d/7d） */
    private static final String QWEATHER_FORECAST_URL = "https://devapi.qweather.com/v7/weather/{days}d";

    /**
     * 构造函数
     *
     * @param jdbcTemplate 数据库访问
     * @param config       扩展配置
     * @apiNote restTemplate 和 objectMapper 在构造函数中初始化
     */
    public WeatherDataService(JdbcTemplate jdbcTemplate, NutritionExtensionConfig config) {
        this.jdbcTemplate = jdbcTemplate;
        this.config = config;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 获取当日天气，优先缓存
     *
     * @param cityCode 城市编码
     * @return 天气信息，API不可用时返回NORMAL默认值
     */
    public WeatherInfo getCurrentWeather(String cityCode) {
        // 查缓存表
        WeatherInfo cached = queryFromCache(cityCode, LocalDate.now(), false);
        if (cached != null && !isCacheExpired(cached, config.getWeather().getCacheTtlHours())) {
            return cached;
        }

        // 刷新数据
        try {
            WeatherInfo fresh = fetchFromExternalApi(cityCode, false);
            if (fresh != null) {
                saveToCache(fresh);
                return fresh;
            }
        } catch (Exception e) {
            log.warn("获取天气数据失败: cityCode={}, 使用缓存或默认值", cityCode, e);
        }

        // 返回过期缓存或默认值
        return cached != null ? cached : buildDefaultWeather(cityCode, LocalDate.now());
    }

    /**
     * 获取未来N天天气预报
     *
     * @param cityCode 城市编码
     * @param days     预报天数 (1-7)
     * @return 天气预报列表
     */
    public List<WeatherInfo> getForecastWeather(String cityCode, int days) {
        LocalDate today = LocalDate.now();
        // 查缓存
        List<WeatherInfo> cached = queryForecastFromCache(cityCode, today, days);
        if (!cached.isEmpty() && !isForecastCacheExpired(cached, config.getWeather().getForecastCacheTtlHours())) {
            return cached;
        }

        // 刷新数据
        try {
            List<WeatherInfo> fresh = fetchForecastFromExternalApi(cityCode, days);
            if (!fresh.isEmpty()) {
                fresh.forEach(this::saveToCache);
                return fresh;
            }
        } catch (Exception e) {
            log.warn("获取天气预报数据失败: cityCode={}, 使用缓存或默认值", cityCode, e);
        }

        // 返回过期缓存或默认值
        if (!cached.isEmpty()) {
            return cached;
        }

        // 构建默认预报
        return Collections.nCopies(days, buildDefaultWeather(cityCode, null));
    }

    /**
     * 获取天气类型
     *
     * @param cityCode 城市编码
     * @return 天气类型
     */
    public WeatherType getWeatherType(String cityCode) {
        WeatherInfo weather = getCurrentWeather(cityCode);
        return weather != null ? weather.getWeatherType() : WeatherType.NORMAL;
    }

    /**
     * 计算天气对价格的影响因子
     *
     * @param weatherType 天气类型
     * @return 价格影响因子 (1.0 = 无影响)
     */
    public BigDecimal calculatePriceImpactFactor(WeatherType weatherType) {
        return BigDecimal.valueOf(weatherType.getPriceImpactFactor());
    }

    // ==================== 缓存操作 ====================

    /**
     * 从缓存表查询天气数据
     *
     * @param cityCode   城市编码
     * @param date       天气日期
     * @param isForecast 是否为预报数据
     * @return 天气信息，查询失败或不存在时返回 null
     */
    private WeatherInfo queryFromCache(String cityCode, LocalDate date, boolean isForecast) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT city_code, weather_date, weather_type, high_temp, low_temp, " +
                            "wind_level, humidity, is_forecast, updated_at FROM weather_cache " +
                            "WHERE city_code = ? AND weather_date = ? AND is_forecast = ?",
                    cityCode, date, isForecast);

            if (rows.isEmpty()) {
                return null;
            }

            Map<String, Object> row = rows.get(0);
            WeatherInfo info = WeatherInfo.builder()
                    .cityCode((String) row.get("city_code"))
                    .date(date)
                    .weatherType(WeatherType.valueOf((String) row.get("weather_type")))
                    .highTemp(row.get("high_temp") != null ? new BigDecimal(row.get("high_temp").toString()) : null)
                    .lowTemp(row.get("low_temp") != null ? new BigDecimal(row.get("low_temp").toString()) : null)
                    .windLevel((String) row.get("wind_level"))
                    .humidity(row.get("humidity") != null ? new BigDecimal(row.get("humidity").toString()) : null)
                    .forecast(isForecast)
                    .build();

            // 保存缓存更新时间，用于过期判断
            Object updatedAt = row.get("updated_at");
            if (updatedAt instanceof LocalDateTime ldt) {
                info.setCachedAt(ldt);
            }
            return info;
        } catch (Exception e) {
            log.warn("查询天气缓存失败: cityCode={}", cityCode, e);
            return null;
        }
    }

    /**
     * 从缓存表查询天气预报
     *
     * @param cityCode  城市编码
     * @param startDate 起始日期
     * @param days      预报天数
     * @return 天气预报列表，查询失败时返回空列表
     */
    private List<WeatherInfo> queryForecastFromCache(String cityCode, LocalDate startDate, int days) {
        try {
            LocalDate endDate = startDate.plusDays(days);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT city_code, weather_date, weather_type, high_temp, low_temp, " +
                            "wind_level, humidity, updated_at FROM weather_cache " +
                            "WHERE city_code = ? AND weather_date BETWEEN ? AND ? AND is_forecast = 1 " +
                            "ORDER BY weather_date",
                    cityCode, startDate, endDate);

            return rows.stream().map(row -> {
                WeatherInfo info = WeatherInfo.builder()
                        .cityCode((String) row.get("city_code"))
                        .date(LocalDate.parse(row.get("weather_date").toString()))
                        .weatherType(WeatherType.valueOf((String) row.get("weather_type")))
                        .highTemp(row.get("high_temp") != null ? new BigDecimal(row.get("high_temp").toString()) : null)
                        .lowTemp(row.get("low_temp") != null ? new BigDecimal(row.get("low_temp").toString()) : null)
                        .windLevel((String) row.get("wind_level"))
                        .humidity(row.get("humidity") != null ? new BigDecimal(row.get("humidity").toString()) : null)
                        .forecast(true)
                        .build();
                Object updatedAt = row.get("updated_at");
                if (updatedAt instanceof LocalDateTime ldt) {
                    info.setCachedAt(ldt);
                }
                return info;
            }).toList();
        } catch (Exception e) {
            log.warn("查询天气预报缓存失败: cityCode={}", cityCode, e);
            return Collections.emptyList();
        }
    }

    /**
     * 保存天气数据到缓存表
     *
     * @param info 天气信息
     */
    private void saveToCache(WeatherInfo info) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO weather_cache (city_code, weather_date, weather_type, high_temp, low_temp, " +
                            "wind_level, humidity, is_forecast) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE weather_type = VALUES(weather_type), high_temp = VALUES(high_temp), "
                            +
                            "low_temp = VALUES(low_temp), wind_level = VALUES(wind_level), humidity = VALUES(humidity)",
                    info.getCityCode(),
                    info.getDate(),
                    info.getWeatherType().name(),
                    info.getHighTemp(),
                    info.getLowTemp(),
                    info.getWindLevel(),
                    info.getHumidity(),
                    info.isForecast());
        } catch (Exception e) {
            log.warn("保存天气缓存失败: cityCode={}", info.getCityCode(), e);
        }
    }

    // ==================== 外部API对接 ====================

    /**
     * 从外部天气API获取当前天气
     *
     * <p>
     * 根据 provider 配置选择对应的天气API：
     * <ul>
     * <li>qweather — 和风天气（默认）</li>
     * <li>custom — 自定义API地址</li>
     * </ul>
     * </p>
     *
     * @param cityCode   城市编码
     * @param isForecast 是否为预报数据
     * @return 天气信息，API不可用时返回 null
     */
    private WeatherInfo fetchFromExternalApi(String cityCode, boolean isForecast) {
        String provider = config.getWeather().getProvider();
        String apiKey = config.getWeather().getApiKey();

        if (apiKey == null || apiKey.isBlank()) {
            log.info("天气API Key未配置，跳过外部API调用: cityCode={}", cityCode);
            return null;
        }

        if ("qweather".equalsIgnoreCase(provider)) {
            return fetchFromQWeather(cityCode, isForecast);
        } else if ("custom".equalsIgnoreCase(provider)) {
            return fetchFromCustomApi(cityCode, isForecast);
        }

        log.warn("未知的天气API提供商: {}, cityCode={}", provider, cityCode);
        return null;
    }

    /**
     * 从外部天气API获取天气预报
     *
     * @param cityCode 城市编码
     * @param days     预报天数
     * @return 天气预报列表，API不可用时返回空列表
     */
    private List<WeatherInfo> fetchForecastFromExternalApi(String cityCode, int days) {
        String provider = config.getWeather().getProvider();
        String apiKey = config.getWeather().getApiKey();

        if (apiKey == null || apiKey.isBlank()) {
            log.info("天气API Key未配置，跳过外部API调用: cityCode={}", cityCode);
            return Collections.emptyList();
        }

        if ("qweather".equalsIgnoreCase(provider)) {
            return fetchForecastFromQWeather(cityCode, days);
        } else if ("custom".equalsIgnoreCase(provider)) {
            return fetchForecastFromCustomApi(cityCode, days);
        }

        log.warn("未知的天气API提供商: {}, cityCode={}", provider, cityCode);
        return Collections.emptyList();
    }

    // ==================== 和风天气 API ====================

    /**
     * 调用和风天气实时天气 API
     *
     * <p>
     * API文档: https://dev.qweather.com/docs/api/weather/weather-now/
     * 响应格式: { "code": "200", "now": { "temp", "text", "windScale", "humidity" } }
     * </p>
     *
     * @param cityCode   城市编码
     * @param isForecast 是否为预报数据
     * @return 天气信息，API调用失败时返回 null
     */
    private WeatherInfo fetchFromQWeather(String cityCode, boolean isForecast) {
        try {
            String apiKey = config.getWeather().getApiKey();
            String url = QWEATHER_NOW_URL + "?location=" + cityCode + "&key=" + apiKey;

            log.debug("调用和风天气实时API: cityCode={}", cityCode);
            String response = restTemplate.getForObject(url, String.class);

            if (response == null) {
                log.warn("和风天气API返回空响应: cityCode={}", cityCode);
                return null;
            }

            JsonNode root = objectMapper.readTree(response);
            String code = root.path("code").asText();

            if (!"200".equals(code)) {
                log.warn("和风天气API返回错误: code={}, cityCode={}", code, cityCode);
                return null;
            }

            JsonNode now = root.path("now");
            String text = now.path("text").asText("");
            String temp = now.path("temp").asText("");
            String windScale = now.path("windScale").asText("");
            String humidity = now.path("humidity").asText("");

            return WeatherInfo.builder()
                    .cityCode(cityCode)
                    .date(LocalDate.now())
                    .weatherType(mapWeatherType(text))
                    .description(text)
                    .highTemp(temp.isEmpty() ? null : new BigDecimal(temp))
                    .lowTemp(temp.isEmpty() ? null : new BigDecimal(temp))
                    .windLevel(windScale)
                    .humidity(humidity.isEmpty() ? null : new BigDecimal(humidity))
                    .forecast(isForecast)
                    .build();

        } catch (Exception e) {
            log.warn("调用和风天气实时API失败: cityCode={}", cityCode, e);
            return null;
        }
    }

    /**
     * 调用和风天气预报 API
     *
     * <p>
     * API文档: https://dev.qweather.com/docs/api/weather/weather-daily-forecast/
     * 响应格式: { "code": "200", "daily": [{ "fxDate", "textDay", "tempMax", "tempMin",
     * "windScaleDay", "humidity" }] }
     * </p>
     *
     * @param cityCode 城市编码
     * @param days     预报天数
     * @return 天气预报列表，API调用失败时返回空列表
     */
    private List<WeatherInfo> fetchForecastFromQWeather(String cityCode, int days) {
        try {
            String apiKey = config.getWeather().getApiKey();
            // 和风天气免费版支持3天，付费版7天
            int apiDays = Math.min(days, 7);
            String url = QWEATHER_FORECAST_URL.replace("{days}d", apiDays + "d")
                    + "?location=" + cityCode + "&key=" + apiKey;

            log.debug("调用和风天气预报API: cityCode={}, days={}", cityCode, apiDays);
            String response = restTemplate.getForObject(url, String.class);

            if (response == null) {
                log.warn("和风天气预报API返回空响应: cityCode={}", cityCode);
                return Collections.emptyList();
            }

            JsonNode root = objectMapper.readTree(response);
            String code = root.path("code").asText();

            if (!"200".equals(code)) {
                log.warn("和风天气预报API返回错误: code={}, cityCode={}", code, cityCode);
                return Collections.emptyList();
            }

            List<WeatherInfo> result = new ArrayList<>();
            JsonNode daily = root.path("daily");

            for (int i = 0; i < Math.min(daily.size(), days); i++) {
                JsonNode day = daily.get(i);
                String fxDate = day.path("fxDate").asText("");
                String textDay = day.path("textDay").asText("");
                String tempMax = day.path("tempMax").asText("");
                String tempMin = day.path("tempMin").asText("");
                String windScaleDay = day.path("windScaleDay").asText("");
                String humidity = day.path("humidity").asText("");

                WeatherInfo info = WeatherInfo.builder()
                        .cityCode(cityCode)
                        .date(fxDate.isEmpty() ? LocalDate.now().plusDays(i) : LocalDate.parse(fxDate))
                        .weatherType(mapWeatherType(textDay))
                        .description(textDay)
                        .highTemp(tempMax.isEmpty() ? null : new BigDecimal(tempMax))
                        .lowTemp(tempMin.isEmpty() ? null : new BigDecimal(tempMin))
                        .windLevel(windScaleDay)
                        .humidity(humidity.isEmpty() ? null : new BigDecimal(humidity))
                        .forecast(true)
                        .build();
                result.add(info);
            }

            return result;

        } catch (Exception e) {
            log.warn("调用和风天气预报API失败: cityCode={}", cityCode, e);
            return Collections.emptyList();
        }
    }

    // ==================== 自定义 API ====================

    /**
     * 调用自定义天气API（当前天气）
     *
     * <p>
     * 自定义API需返回JSON格式：
     * { "weather_type": "NORMAL", "description": "晴", "high_temp": 25.0,
     * "low_temp": 15.0,
     * "wind_level": "3", "humidity": 60.0 }
     * </p>
     *
     * @param cityCode   城市编码
     * @param isForecast 是否为预报数据
     * @return 天气信息，API调用失败时返回 null
     */
    private WeatherInfo fetchFromCustomApi(String cityCode, boolean isForecast) {
        try {
            String apiUrl = config.getWeather().getApiUrl();
            String apiKey = config.getWeather().getApiKey();

            if (apiUrl == null || apiUrl.isBlank()) {
                log.warn("自定义天气API地址未配置: cityCode={}", cityCode);
                return null;
            }

            String url = apiUrl + "?cityCode=" + cityCode + "&apiKey=" + apiKey;
            String response = restTemplate.getForObject(url, String.class);

            if (response == null) {
                return null;
            }

            JsonNode root = objectMapper.readTree(response);
            return WeatherInfo.builder()
                    .cityCode(cityCode)
                    .date(LocalDate.now())
                    .weatherType(WeatherType.valueOf(root.path("weather_type").asText("NORMAL")))
                    .description(root.path("description").asText(""))
                    .highTemp(
                            root.path("high_temp").isNumber() ? new BigDecimal(root.path("high_temp").asText()) : null)
                    .lowTemp(root.path("low_temp").isNumber() ? new BigDecimal(root.path("low_temp").asText()) : null)
                    .windLevel(root.path("wind_level").asText(""))
                    .humidity(root.path("humidity").isNumber() ? new BigDecimal(root.path("humidity").asText()) : null)
                    .forecast(isForecast)
                    .build();

        } catch (Exception e) {
            log.warn("调用自定义天气API失败: cityCode={}", cityCode, e);
            return null;
        }
    }

    /**
     * 调用自定义天气API（天气预报）
     *
     * @param cityCode 城市编码
     * @param days     预报天数
     * @return 天气预报列表，API调用失败时返回空列表
     */
    private List<WeatherInfo> fetchForecastFromCustomApi(String cityCode, int days) {
        try {
            String apiUrl = config.getWeather().getApiUrl();
            String apiKey = config.getWeather().getApiKey();

            if (apiUrl == null || apiUrl.isBlank()) {
                return Collections.emptyList();
            }

            String url = apiUrl + "?cityCode=" + cityCode + "&apiKey=" + apiKey + "&days=" + days + "&forecast=true";
            String response = restTemplate.getForObject(url, String.class);

            if (response == null) {
                return Collections.emptyList();
            }

            JsonNode root = objectMapper.readTree(response);
            List<WeatherInfo> result = new ArrayList<>();

            if (root.isArray()) {
                for (JsonNode item : root) {
                    WeatherInfo info = WeatherInfo.builder()
                            .cityCode(cityCode)
                            .date(item.path("date").isTextual()
                                    ? LocalDate.parse(item.path("date").asText())
                                    : LocalDate.now().plusDays(result.size()))
                            .weatherType(WeatherType.valueOf(item.path("weather_type").asText("NORMAL")))
                            .description(item.path("description").asText(""))
                            .highTemp(
                                    item.path("high_temp").isNumber() ? new BigDecimal(item.path("high_temp").asText())
                                            : null)
                            .lowTemp(item.path("low_temp").isNumber() ? new BigDecimal(item.path("low_temp").asText())
                                    : null)
                            .windLevel(item.path("wind_level").asText(""))
                            .humidity(item.path("humidity").isNumber() ? new BigDecimal(item.path("humidity").asText())
                                    : null)
                            .forecast(true)
                            .build();
                    result.add(info);
                }
            }

            return result;

        } catch (Exception e) {
            log.warn("调用自定义天气API(预报)失败: cityCode={}", cityCode, e);
            return Collections.emptyList();
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 将和风天气描述文本映射为 WeatherType
     *
     * <p>
     * 和风天气天气现象中文关键词映射规则：
     * <ul>
     * <li>台风 → TYPHOON</li>
     * <li>暴雨/大暴雨/特大暴雨/雷阵雨 → STORM</li>
     * <li>寒潮/暴雪/大雪 → COLD_WAVE</li>
     * <li>高温/酷热/炎热 → HIGH_TEMP</li>
     * <li>其他 → NORMAL</li>
     * </ul>
     * </p>
     *
     * @param weatherText 天气描述文本
     * @return 对应的天气类型
     */
    private WeatherType mapWeatherType(String weatherText) {
        if (weatherText == null || weatherText.isBlank()) {
            return WeatherType.NORMAL;
        }

        // 台风
        if (weatherText.contains("台风")) {
            return WeatherType.TYPHOON;
        }
        // 暴雨/雷暴
        if (weatherText.contains("暴雨") || weatherText.contains("雷阵雨") || weatherText.contains("雷暴")) {
            return WeatherType.STORM;
        }
        // 寒潮/暴雪
        if (weatherText.contains("寒潮") || weatherText.contains("暴雪") || weatherText.contains("大雪")) {
            return WeatherType.COLD_WAVE;
        }
        // 高温
        if (weatherText.contains("高温") || weatherText.contains("酷热") || weatherText.contains("炎热")) {
            return WeatherType.HIGH_TEMP;
        }

        return WeatherType.NORMAL;
    }

    /**
     * 构建默认天气信息 (API不可用时的降级方案)
     *
     * @param cityCode 城市编码
     * @param date     天气日期
     * @return 默认的 NORMAL 类型天气信息
     */
    private WeatherInfo buildDefaultWeather(String cityCode, LocalDate date) {
        return WeatherInfo.builder()
                .cityCode(cityCode)
                .date(date)
                .weatherType(WeatherType.NORMAL)
                .description("正常")
                .forecast(false)
                .build();
    }

    /**
     * 判断缓存是否过期
     *
     * <p>
     * 基于 WeatherInfo 中的 cachedAt 时间戳与配置的 TTL 比较。
     * </p>
     *
     * @param cached   缓存的天气信息
     * @param ttlHours 缓存有效期（小时）
     * @return 是否已过期
     */
    private boolean isCacheExpired(WeatherInfo cached, int ttlHours) {
        if (cached.getCachedAt() == null) {
            // 无更新时间，视为有效（兼容旧数据）
            return false;
        }
        LocalDateTime expiresAt = cached.getCachedAt().plusHours(ttlHours);
        return LocalDateTime.now().isAfter(expiresAt);
    }

    /**
     * 判断预报缓存是否过期
     *
     * @param cached   缓存的天气预报列表
     * @param ttlHours 缓存有效期（小时）
     * @return 是否已过期
     */
    private boolean isForecastCacheExpired(List<WeatherInfo> cached, int ttlHours) {
        if (cached.isEmpty()) {
            return true;
        }
        // 取第一条记录的缓存时间判断
        LocalDateTime cachedAt = cached.get(0).getCachedAt();
        if (cachedAt == null) {
            return false;
        }
        LocalDateTime expiresAt = cachedAt.plusHours(ttlHours);
        return LocalDateTime.now().isAfter(expiresAt);
    }
}
