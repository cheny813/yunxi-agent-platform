package io.yunxi.platform.business.nutrition.price;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 价格模式枚举
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Getter
@AllArgsConstructor
public enum PriceMode {

    /** 集中采购固定价格模式 */
    FIXED("集中采购"),

    /** 市场波动价格模式 */
    FLUCTUATION("市场波动");

    /** 显示名称 */
    private final String displayName;
}
