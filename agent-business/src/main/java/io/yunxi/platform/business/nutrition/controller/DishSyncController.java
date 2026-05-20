package io.yunxi.platform.business.nutrition.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import io.yunxi.platform.business.nutrition.sync.SchoolDishVectorSyncService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 菜品向量同步控制器
 *
 * <p>
 * 提供手动触发菜品向量同步的API接口，支持公共菜品和学校专属菜品的同步管理
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@RestController
@RequestMapping("/api/dish-sync")
public class DishSyncController {

    /** 菜品向量同步服务（可选依赖，未启用时为null） */
    @Autowired(required = false)
    private SchoolDishVectorSyncService dishVectorSyncService;

    /**
     * 手动触发全量菜品向量同步（公共菜品 + 所有学校专属菜品）
     *
     * @param force 是否强制同步（true时跳过needSync检查）
     * @return 同步结果
     */
    @PostMapping("/trigger")
    public Map<String, Object> triggerSync(@RequestParam(defaultValue = "false") boolean force) {
        log.info("收到手动同步请求, force={}", force);

        if (dishVectorSyncService == null) {
            log.warn("菜品向量同步服务未启用");
            return Map.of(
                    "success", false,
                    "message", "菜品向量同步服务未启用，请检查配置");
        }

        try {
            SchoolDishVectorSyncService.SyncResult result = dishVectorSyncService.manualSync(force);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", result.isSuccess());
            response.put("message", result.getMessage());
            response.put("duration", result.getDuration() + "ms");
            return response;
        } catch (Exception e) {
            log.error("手动同步失败", e);
            return Map.of(
                    "success", false,
                    "message", "同步失败: " + e.getMessage());
        }
    }

    /**
     * 手动触发指定学校的菜品向量同步
     *
     * @param schoolId 学校ID
     * @return 同步结果
     */
    @PostMapping("/trigger/{schoolId}")
    public Map<String, Object> triggerSchoolSync(@PathVariable Long schoolId) {
        log.info("收到学校菜品同步请求, schoolId={}", schoolId);

        if (dishVectorSyncService == null) {
            log.warn("菜品向量同步服务未启用");
            return Map.of(
                    "success", false,
                    "message", "菜品向量同步服务未启用，请检查配置");
        }

        try {
            long startTime = System.currentTimeMillis();
            dishVectorSyncService.syncSchoolDishes(schoolId);
            long duration = System.currentTimeMillis() - startTime;

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", "学校" + schoolId + "菜品同步完成");
            response.put("schoolId", schoolId);
            response.put("duration", duration + "ms");
            return response;
        } catch (Exception e) {
            log.error("学校{}菜品同步失败", schoolId, e);
            return Map.of(
                    "success", false,
                    "message", "学校" + schoolId + "菜品同步失败: " + e.getMessage());
        }
    }

    /**
     * 获取同步状态
     *
     * @return 同步服务状态（包含集合记录数、已同步学校列表等信息）
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        if (dishVectorSyncService == null) {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("enabled", false);
            status.put("message", "菜品向量同步服务未启用");
            return status;
        }

        return dishVectorSyncService.getSyncStatus();
    }

    /**
     * 获取已同步的学校列表
     *
     * @return 已同步学校ID列表
     */
    @GetMapping("/schools")
    public Map<String, Object> getSyncedSchools() {
        if (dishVectorSyncService == null) {
            return Map.of(
                    "enabled", false,
                    "message", "菜品向量同步服务未启用",
                    "schools", List.of());
        }

        List<Long> schools = dishVectorSyncService.getSyncedSchools();
        return Map.of(
                "enabled", true,
                "count", schools.size(),
                "schools", schools);
    }
}
