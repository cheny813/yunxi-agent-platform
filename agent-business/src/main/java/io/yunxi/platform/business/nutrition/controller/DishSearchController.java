package io.yunxi.platform.business.nutrition.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import io.yunxi.platform.business.nutrition.sync.SchoolDishVectorSyncService;
import io.yunxi.platform.business.nutrition.sync.SchoolDishVectorSyncService.DishData;

import java.util.List;
import java.util.Map;

/**
 * 菜品搜索控制器
 *
 * <p>
 * 提供菜品向量搜索接口，支持按学校维度检索
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/api/dish-search")
public class DishSearchController {

    /** 菜品向量同步服务（可选依赖，未启用时为null） */
    @Autowired(required = false)
    private SchoolDishVectorSyncService dishVectorSyncService;

    /**
     * 按学校ID搜索菜品
     *
     * @param schoolId 学校ID
     * @param query 查询文本
     * @param topK 返回数量（默认10）
     * @return 匹配的菜品列表，包含success、query、schoolId、count、data等字段
     * @throws Exception 当向量搜索服务异常时可能抛出
     */
    @GetMapping("/by-school")
    public Map<String, Object> searchBySchool(
            @RequestParam Long schoolId,
            @RequestParam String query,
            @RequestParam(defaultValue = "10") int topK) {

        log.info("按学校搜索菜品: schoolId={}, query={}, topK={}", schoolId, query, topK);

        if (dishVectorSyncService == null) {
            return Map.of(
                    "success", false,
                    "message", "菜品向量同步服务未启用"
            );
        }

        try {
            List<DishData> results = dishVectorSyncService.searchDishesBySchool(schoolId, query, topK);
            return Map.of(
                    "success", true,
                    "query", query,
                    "schoolId", schoolId,
                    "count", results.size(),
                    "data", results
            );
        } catch (Exception e) {
            log.error("搜索菜品失败", e);
            return Map.of(
                    "success", false,
                    "message", "搜索失败: " + e.getMessage()
            );
        }
    }
}
