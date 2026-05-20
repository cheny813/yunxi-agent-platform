package io.yunxi.platform.business.nutrition.controller;

import io.yunxi.platform.business.nutrition.service.DynamicQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 动态查询控制器
 * <p>
 * 提供基于自然语言描述的动态数据查询能力
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/nutrition/dynamic-query")
public class DynamicQueryController {

    /** 动态查询服务（可选依赖，未启用时为null） */
    @Autowired(required = false)
    private DynamicQueryService dynamicQueryService;

    /**
     * 根据描述查询数据
     *
     * @param request 请求体
     * @return 查询结果
     */
    @PostMapping("/query")
    public Map<String, Object> queryByDescription(@RequestBody Map<String, String> request) {
        String description = request.get("description");
        String format = request.getOrDefault("format", "list"); // list, report, json

        log.info("收到动态查询请求: description={}, format={}", description, format);

        try {
            if (dynamicQueryService == null) {
                return Map.of(
                        "success", false,
                        "message", "动态查询服务未启用"
                );
            }

            // 执行查询
            DynamicQueryService.QueryResult result = dynamicQueryService.queryByDescription(description);

            if (result.getError() != null) {
                return Map.of(
                        "success", false,
                        "message", "查询失败: " + result.getError(),
                        "sql", result.getSql()
                );
            }

            // 根据format返回不同格式
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("tableName", result.getTableName());
            response.put("sql", result.getSql());
            response.put("rowCount", result.getRowCount());
            response.put("duration", result.getDuration());
            response.put("schema", result.getSchema());

            if ("report".equals(format)) {
                response.put("report", dynamicQueryService.generateStatisticsReport(result));
            } else if ("list".equals(format)) {
                response.put("formatted", dynamicQueryService.formatDataList(result));
            } else {
                response.put("data", result.getData());
            }

            return response;

        } catch (Exception e) {
            log.error("动态查询失败", e);
            return Map.of(
                    "success", false,
                    "message", "查询异常: " + e.getMessage()
            );
        }
    }

    /**
     * 快速查询（GET请求）
     *
     * @param description 自然语言描述
     * @return 查询结果
     */
    @GetMapping("/query")
    public Map<String, Object> quickQuery(@RequestParam String description,
                                         @RequestParam(defaultValue = "list") String format) {
        Map<String, String> request = new HashMap<>();
        request.put("description", description);
        request.put("format", format);
        return queryByDescription(request);
    }

    /**
     * 获取表列表
     *
     * @return 可查询的表列表
     */
    @GetMapping("/tables")
    public Map<String, Object> listTables() {
        return Map.of(
                "success", true,
                "tables", Map.of(
                        "dish_library", "菜品库表",
                        "food_ingredient", "食材表",
                        "nutrient", "营养素表",
                        "nutrient_standard", "营养标准表",
                        "dish_class", "菜品分类表",
                        "food_ingredient_class", "食材分类表",
                        "nutrient_standard_dcn", "营养标准详情表",
                        "cook_book_score_index_class", "评分指标类别表",
                        "cook_book_score_index_detail", "评分指标明细表"
                ),
                "queryExamples", Map.of(
                        "菜品库", List.of(
                                "查看最近10个菜品",
                                "查看所有菜品按更新时间排序",
                                "统计菜品总数",
                                "按菜品类型分组统计数量"
                        ),
                        "食材", List.of(
                                "查看最近10个食材",
                                "查看所有食材",
                                "统计食材总数"
                        ),
                        "营养素", List.of(
                                "查看所有营养素",
                                "查看最近更新的营养素"
                        )
                )
        );
    }

    /**
     * 获取表结构
     *
     * @param tableName 表名
     * @return 表结构
     */
    @GetMapping("/schema/{tableName}")
    public Map<String, Object> getTableSchema(@PathVariable String tableName) {
        try {
            if (dynamicQueryService == null) {
                return Map.of(
                        "success", false,
                        "message", "动态查询服务未启用"
                );
            }

            DynamicQueryService.QueryResult result =
                    dynamicQueryService.queryByDescription("查看" + tableName + "的结构");

            return Map.of(
                    "success", true,
                    "tableName", tableName,
                    "schema", result.getSchema()
            );

        } catch (Exception e) {
            log.error("获取表结构失败: {}", tableName, e);
            return Map.of(
                    "success", false,
                    "message", "获取表结构失败: " + e.getMessage()
            );
        }
    }
}
