package io.yunxi.platform.business.nutrition.weather;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 天气信息 DTO
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherInfo {

    /** 城市编码 */
    private String cityCode;

    /** 天气日期 */
    private LocalDate date;

    /** 天气类型 */
    private WeatherType weatherType;

    /** 天气描述文本 */
    private String description;

    /** 最高温度(℃) */
    private BigDecimal highTemp;

    /** 最低温度(℃) */
    private BigDecimal lowTemp;

    /** 风力等级 */
    private String windLevel;

    /** 湿度(%) */
    private BigDecimal humidity;

    /** 是否为预报数据 */
    private boolean forecast;

    /** 缓存更新时间（内部使用，不持久化到API） */
    private LocalDateTime cachedAt;
}
