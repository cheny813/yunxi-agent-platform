-- ============================================================
-- 营养扩展功能表 (V2)
-- 所有表默认不启用，需在配置中开启 nutrition-extension.*.enabled=true
-- ============================================================

-- 天气数据缓存
CREATE TABLE IF NOT EXISTS weather_cache (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    city_code VARCHAR(20) NOT NULL COMMENT '城市编码',
    weather_date DATE NOT NULL COMMENT '天气日期',
    weather_type VARCHAR(30) NOT NULL COMMENT '天气类型: TYPHOON/STORM/COLD_WAVE/HIGH_TEMP/NORMAL',
    high_temp DECIMAL(5,1) COMMENT '最高温度(℃)',
    low_temp DECIMAL(5,1) COMMENT '最低温度(℃)',
    wind_level VARCHAR(10) COMMENT '风力等级',
    humidity DECIMAL(5,1) COMMENT '湿度(%)',
    is_forecast TINYINT(1) DEFAULT 0 COMMENT '是否为预报数据: 0=实际 1=预报',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_city_date (city_code, weather_date, is_forecast),
    INDEX idx_city (city_code),
    INDEX idx_date (weather_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='天气数据缓存';

-- 市场价格缓存
CREATE TABLE IF NOT EXISTS market_price_cache (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ingredient_id BIGINT NOT NULL COMMENT '食材ID',
    district_code VARCHAR(20) NOT NULL COMMENT '区县编码',
    price DECIMAL(10,2) NOT NULL COMMENT '单价(元/kg)',
    price_source VARCHAR(30) NOT NULL COMMENT '价格来源: MARKET(市场)/PROCUREMENT(采购)/FIXED(固定)',
    price_date DATE NOT NULL COMMENT '价格日期',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_ingredient_district_date (ingredient_id, district_code, price_date),
    INDEX idx_district (district_code),
    INDEX idx_ingredient (ingredient_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='市场价格缓存';

-- 区县价格模式配置
CREATE TABLE IF NOT EXISTS district_price_mode (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    district_code VARCHAR(20) NOT NULL COMMENT '区县编码',
    price_mode VARCHAR(20) NOT NULL COMMENT '价格模式: FIXED(集中采购)/FLUCTUATION(市场波动)',
    fixed_price_config JSON COMMENT '集中采购固定价格配置(食材ID→价格映射)',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_district (district_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='区县价格模式配置';

-- 价格预测历史
CREATE TABLE IF NOT EXISTS price_prediction_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ingredient_id BIGINT NOT NULL COMMENT '食材ID',
    district_code VARCHAR(20) NOT NULL COMMENT '区县编码',
    predicted_price DECIMAL(10,2) NOT NULL COMMENT '预测价格(元/kg)',
    confidence_lower DECIMAL(10,2) NOT NULL COMMENT '95%置信区间下限',
    confidence_upper DECIMAL(10,2) NOT NULL COMMENT '95%置信区间上限',
    prediction_date DATE NOT NULL COMMENT '预测的目标日期',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_lookup (ingredient_id, district_code, prediction_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='价格预测历史';
