package io.yunxi.platform.business.nutrition.price;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 价格数据同步定时调度
 *
 * <p>
 * 定时触发 {@link PriceDataSyncService} 同步食材价格数据到 market_price_cache 缓存表。
 * 默认每天凌晨3点执行，通过 {@code nutrition-extension.price.sync-cron} 配置。
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
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "nutrition-extension.price.enabled", havingValue = "true")
public class PriceDataSyncScheduler {

    /** 价格数据同步服务 */
    private final PriceDataSyncService priceDataSyncService;

    /**
     * 定时同步食材价格数据
     *
     * <p>
     * 默认每天凌晨3点执行。可通过 nutrition-extension.price.sync-cron 配置调整。
     * </p>
     */
    @Scheduled(cron = "${nutrition-extension.price.sync-cron:0 0 3 * * ?}")
    public void syncPriceData() {
        log.info("开始定时同步食材价格数据");
        try {
            int count = priceDataSyncService.syncAllDistricts();
            log.info("定时同步食材价格数据完成: 同步{}条记录", count);
        } catch (Exception e) {
            log.error("定时同步食材价格数据失败", e);
        }
    }
}
