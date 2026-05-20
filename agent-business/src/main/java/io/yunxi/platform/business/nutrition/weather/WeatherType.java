package io.yunxi.platform.business.nutrition.weather;

/**
 * 天气类型枚举
 *
 * <p>
 * 定义影响食材价格的天气类型，每种类型对应一个价格影响因子。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public enum WeatherType {

    /** 台风 — 价格影响因子 1.30 */
    TYPHOON("台风", 1.30),

    /** 暴雨 — 价格影响因子 1.20 */
    STORM("暴雨", 1.20),

    /** 寒潮 — 价格影响因子 1.15 */
    COLD_WAVE("寒潮", 1.15),

    /** 高温 — 价格影响因子 1.10 */
    HIGH_TEMP("高温", 1.10),

    /** 正常 — 价格影响因子 1.00 */
    NORMAL("正常", 1.00);

    /** 显示名称 */
    private final String displayName;
    /** 价格影响因子（1.0 表示无影响，>1.0 表示价格上涨） */
    private final double priceImpactFactor;

    /**
     * 天气类型构造函数
     *
     * @param displayName       显示名称
     * @param priceImpactFactor 价格影响因子
     */
    WeatherType(String displayName, double priceImpactFactor) {
        this.displayName = displayName;
        this.priceImpactFactor = priceImpactFactor;
    }

    /**
     * 获取显示名称
     *
     * @return 天气类型中文显示名称
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 获取价格影响因子
     *
     * @return 价格影响因子，1.0 表示无影响
     */
    public double getPriceImpactFactor() {
        return priceImpactFactor;
    }

    /**
     * 根据天气描述文本识别天气类型
     *
     * @param weatherDesc 天气描述（如 "暴雨", "晴" 等）
     * @return 天气类型
     */
    public static WeatherType fromDescription(String weatherDesc) {
        if (weatherDesc == null) {
            return NORMAL;
        }
        String desc = weatherDesc.toLowerCase();
        if (desc.contains("台风") || desc.contains("typhoon")) {
            return TYPHOON;
        }
        if (desc.contains("暴雨") || desc.contains("大雨") || desc.contains("storm")) {
            return STORM;
        }
        if (desc.contains("寒潮") || desc.contains("冰冻") || desc.contains("暴雪") || desc.contains("cold")) {
            return COLD_WAVE;
        }
        if (desc.contains("高温") || desc.contains("酷暑") || desc.contains("heat")) {
            return HIGH_TEMP;
        }
        return NORMAL;
    }
}
