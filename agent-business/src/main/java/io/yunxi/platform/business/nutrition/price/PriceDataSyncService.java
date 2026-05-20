package io.yunxi.platform.business.nutrition.price;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.yunxi.platform.business.nutrition.config.NutritionExtensionConfig;
import io.yunxi.platform.shared.util.TextParserUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 价格数据同步服务
 *
 * <p>
 * 从 MCP 数据库服务查询营养数据库的食材价格数据，同步到本地 market_price_cache 缓存表。
 * 为 FLUCTUATION（市场波动）价格模式提供数据支撑。
 * </p>
 *
 * <p>
 * 同步逻辑：
 * <ol>
 * <li>查询 food_ingredient 表中含 price 字段的食材</li>
 * <li>查询 district_price_mode 表获取已配置的区县列表</li>
 * <li>将食材价格批量写入/更新 market_price_cache 表</li>
 * </ol>
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
@Service
@ConditionalOnProperty(name = "nutrition-extension.price.enabled", havingValue = "true")
public class PriceDataSyncService {

    /** 数据库访问模板 */
    private final JdbcTemplate jdbcTemplate;
    /** 营养扩展配置 */
    private final NutritionExtensionConfig config;
    /** HTTP 请求客户端，用于调用 MCP 数据库服务 */
    private final RestTemplate restTemplate;
    /** JSON 序列化/反序列化工具 */
    private final ObjectMapper objectMapper;

    /** MCP 数据库服务主机地址 */
    @Value("${static-sync.mcp-database.host:localhost}")
    private String mcpDbHost;

    /** MCP 数据库服务端口 */
    @Value("${static-sync.mcp-database.port:40101}")
    private int mcpDbPort;

    /** 行数据正则 */
    private static final Pattern ROW_PATTERN = Pattern.compile("Row \\d+: \\{(.+?)\\}");

    /**
     * 构造函数
     *
     * @param jdbcTemplate 数据库访问
     * @param config       扩展配置
     * @apiNote restTemplate 和 objectMapper 在构造函数中初始化
     */
    public PriceDataSyncService(JdbcTemplate jdbcTemplate, NutritionExtensionConfig config) {
        this.jdbcTemplate = jdbcTemplate;
        this.config = config;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 同步所有区县的食材价格
     *
     * @return 同步的记录数
     */
    public int syncAllDistricts() {
        log.info("开始同步所有区县的食材价格数据");

        // 1. 从 MCP 数据库查询食材价格
        List<IngredientPrice> prices = fetchIngredientPrices();
        if (prices.isEmpty()) {
            log.warn("未查询到食材价格数据，跳过同步");
            return 0;
        }

        // 2. 获取已配置的区县列表
        List<String> districts = getConfiguredDistricts();

        // 3. 批量写入缓存表
        int syncCount = 0;
        for (String districtCode : districts) {
            syncCount += batchUpsertPrices(districtCode, prices);
        }

        log.info("食材价格同步完成: 同步{}个区县, {}条食材, 共{}条记录",
                districts.size(), prices.size(), syncCount);
        return syncCount;
    }

    /**
     * 同步指定区县的食材价格
     *
     * @param districtCode 区县编码
     * @return 同步的记录数
     */
    public int syncDistrict(String districtCode) {
        log.info("开始同步区县食材价格: districtCode={}", districtCode);

        List<IngredientPrice> prices = fetchIngredientPrices();
        if (prices.isEmpty()) {
            log.warn("未查询到食材价格数据，跳过同步: districtCode={}", districtCode);
            return 0;
        }

        int syncCount = batchUpsertPrices(districtCode, prices);
        log.info("区县食材价格同步完成: districtCode={}, 同步{}条记录", districtCode, syncCount);
        return syncCount;
    }

    // ==================== MCP 数据查询 ====================

    /**
     * 从 MCP 数据库查询食材价格
     *
     * <p>
     * 查询 food_ingredient 表中含价格的食材，获取 id、name、price 字段。
     * </p>
     *
     * @return 食材价格列表，查询失败时返回空列表
     */
    private List<IngredientPrice> fetchIngredientPrices() {
        String sql = "SELECT fi_id, fi_name, fi_price FROM food_ingredient WHERE fi_price IS NOT NULL AND fi_price > 0";
        String response = callMcpDatabase(sql);

        if (response == null) {
            log.warn("MCP数据库查询食材价格返回空响应");
            return Collections.emptyList();
        }

        return parseIngredientPrices(response);
    }

    /**
     * 获取已配置的区县列表
     *
     * @return 区县编码列表，查询失败时返回默认区县
     */
    private List<String> getConfiguredDistricts() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT district_code FROM district_price_mode");

            if (rows.isEmpty()) {
                // 如果没有配置，使用默认区县
                return List.of("default");
            }

            return rows.stream()
                    .map(row -> (String) row.get("district_code"))
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.warn("查询区县列表失败，使用默认区县", e);
            return List.of("default");
        }
    }

    // ==================== 数据写入 ====================

    /**
     * 批量写入/更新食材价格到缓存表
     *
     * @param districtCode 区县编码
     * @param prices       食材价格列表
     * @return 成功写入的记录数
     */
    private int batchUpsertPrices(String districtCode, List<IngredientPrice> prices) {
        int count = 0;
        LocalDate today = LocalDate.now();

        for (IngredientPrice price : prices) {
            try {
                jdbcTemplate.update(
                        "INSERT INTO market_price_cache (ingredient_id, district_code, price, price_source, price_date) "
                                +
                                "VALUES (?, ?, ?, 'mcp_sync', ?) " +
                                "ON DUPLICATE KEY UPDATE price = VALUES(price), price_source = VALUES(price_source), " +
                                "price_date = VALUES(price_date)",
                        price.ingredientId, districtCode, price.price, today);
                count++;
            } catch (Exception e) {
                log.warn("写入食材价格缓存失败: ingredientId={}, districtCode={}",
                        price.ingredientId, districtCode, e);
            }
        }

        return count;
    }

    // ==================== MCP 调用 ====================

    /**
     * 调用 MCP 数据库工具执行 SQL 查询
     *
     * @param sql SQL 语句
     * @return MCP 响应体
     */
    private String callMcpDatabase(String sql) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("jsonrpc", "2.0");
            requestBody.put("id", 1);
            requestBody.put("method", "tools/call");

            Map<String, Object> params = new HashMap<>();
            params.put("name", "query_db");

            Map<String, Object> arguments = new HashMap<>();
            arguments.put("sql", sql);
            arguments.put("limit", 10000);
            params.put("arguments", arguments);

            requestBody.put("params", params);

            String url = "http://" + mcpDbHost + ":" + mcpDbPort + "/mcp";
            return restTemplate.postForObject(url, requestBody, String.class);

        } catch (Exception e) {
            log.error("调用MCP数据库查询失败: sql={}", sql, e);
            return null;
        }
    }

    // ==================== 响应解析 ====================

    /**
     * 解析 MCP 数据库查询返回的食材价格数据
     *
     * <p>
     * 响应格式: root.result.content[0].text → "Row 0: {fi_id=1, fi_name=猪肉,
     * fi_price=25.5}\n..."
     * </p>
     *
     * @param response MCP 数据库查询返回的 JSON 响应
     * @return 解析后的食材价格列表
     */
    private List<IngredientPrice> parseIngredientPrices(String response) {
        List<IngredientPrice> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode content = root.path("result").path("content");

            if (content.isArray() && content.size() > 0) {
                String text = content.get(0).path("text").asText();
                Matcher matcher = ROW_PATTERN.matcher(text);

                while (matcher.find()) {
                    String row = matcher.group(1);
                    Long id = TextParserUtil.parseLong(row, "fi_id");
                    String name = TextParserUtil.parseString(row, "fi_name");
                    BigDecimal price = TextParserUtil.parseBigDecimal(row, "fi_price");

                    if (id != null && price != null) {
                        result.add(new IngredientPrice(id, name, price));
                    }
                }
            }
        } catch (Exception e) {
            log.error("解析食材价格数据失败", e);
        }
        return result;
    }

    // ==================== 内部 DTO ====================

    /**
     * 食材价格数据
     */
    private record IngredientPrice(Long ingredientId, String name, BigDecimal price) {
    }
}
