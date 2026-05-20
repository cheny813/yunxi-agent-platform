package io.yunxi.platform.business.nutrition.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import io.yunxi.platform.business.nutrition.sync.StaticDataSyncService;

import java.util.Map;

/**
 * 静态数据同步控制器
 *
 * <p>
 * 提供手动触发静态数据同步的API接口
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/api/static-sync")
public class StaticDataSyncController {

    /** 静态数据同步服务（可选依赖，未启用时为null） */
    @Autowired(required = false)
    private StaticDataSyncService staticDataSyncService;

    /**
     * 手动触发静态数据同步
     *
     * @return 同步结果
     */
    @PostMapping("/trigger")
    public Map<String, Object> triggerSync() {
        log.info("收到静态数据手动同步请求");

        if (staticDataSyncService == null) {
            log.warn("静态数据同步服务未启用");
            return Map.of(
                    "success", false,
                    "message", "静态数据同步服务未启用，请检查配置"
            );
        }

        try {
            staticDataSyncService.syncAll();
            return Map.of(
                    "success", true,
                    "message", "静态数据同步完成"
            );
        } catch (Exception e) {
            log.error("静态数据手动同步失败", e);
            return Map.of(
                    "success", false,
                    "message", "同步失败: " + e.getMessage()
            );
        }
    }

    /**
     * 获取同步状态
     *
     * @return 同步服务状态
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        if (staticDataSyncService == null) {
            return Map.of(
                    "enabled", false,
                    "message", "静态数据同步服务未启用"
            );
        }

        return Map.of(
                "enabled", true,
                "message", "静态数据同步服务已启用"
        );
    }
}
