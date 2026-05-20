package io.yunxi.platform.infra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

/**
 * 静态数据同步 SQL 配置
 *
 * <p>
 * 将 SQL 语句提取到配置文件中，方便维护和修改
 * </p>
 *
 * <p>
 * <b>注意：</b>此处SQL不使用MyBatis Mapper，而是通过MCP（Model Context Protocol）
 * HTTP调用外部营养数据库服务。SQL语句会作为参数传递给MCP服务的query方法执行。
 * </p>
 *
 * <p>
 * MCP调用流程：
 * <ol>
 * <li>StaticDataSyncService 构造SQL语句</li>
 * <li>通过 RestTemplate 调用 MCP 服务 (http://{host}:{port}/mcp)</li>
 * <li>MCP服务执行SQL并返回结果</li>
 * <li>解析返回的JSON格式数据</li>
 * </ol>
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "static-sync.sql")
public class StaticSyncSqlConfig {

    /**
     * 营养标准查询 SQL
     */
    private String nutritionStandards = """
            SELECT ns.id, ns.name, ns.dining_crowd_id, nstdc.crowd_name,
                   ns.intake_standard_id, nsi.standard_name, nsi.age_range,
                   nsd.energy_ratio, nsd.meal_type, nsb.bsfcm_name, nsb.bsfcs_name
            FROM nutrient_standard ns
            LEFT JOIN nutritional_standard_dining_crowd nstdc ON ns.dining_crowd_id = nstdc.id
            LEFT JOIN nutrient_standard_intake nsi ON ns.intake_standard_id = nsi.id
            LEFT JOIN nutrient_standard_dcn nsd ON ns.id = nsd.nutrient_standard_id
            LEFT JOIN nutrient_standard_bsfcm nsb ON ns.id = nsb.nutrient_standard_id
            """;

    /**
     * 食材查询 SQL
     */
    private String ingredients = """
            SELECT id, name, main_class_id, sub_class_id
            FROM food_ingredient
            WHERE deleted = 0
            """;

    /**
     * 单个食材查询 SQL
     */
    private String ingredientById = """
            SELECT id, name, main_class_id, sub_class_id
            FROM food_ingredient
            WHERE id = #{id}
            """;

    /**
     * 食材营养成分查询 SQL
     */
    private String ingredientNutrients = """
            SELECT n.name, fin.nutrient_content, n.unit
            FROM food_ingredient_nutrient fin
            JOIN nutrient n ON fin.nutrient_id = n.id
            WHERE fin.fi_id = #{ingredientId}
            """;

    /**
     * 评分指标查询 SQL
     */
    private String scoreIndex = """
            SELECT cbsic.id, cbsic.class_name, cbsic.weight as class_weight,
                   cbsid.id as detail_id, cbsid.item_name, cbsid.item_value, cbsid.weight as item_weight
            FROM cook_book_score_index_class cbsic
            LEFT JOIN cook_book_score_index_detail cbsid ON cbsic.id = cbsid.class_id
            """;

    /**
     * 食材分类查询 SQL
     */
    private String ingredientClasses = """
            SELECT fimc.id, fimc.class_name, fisc.id as sub_id, fisc.sub_class_name
            FROM food_ingredient_main_class fimc
            LEFT JOIN food_ingredient_class fic ON fimc.id = fic.main_class_id
            LEFT JOIN food_ingredient_sub_class fisc ON fic.sub_class_id = fisc.id
            """;

    /**
     * 菜品分类查询 SQL
     */
    private String dishClasses = """
            SELECT id, class_name, description
            FROM dish_class
            WHERE deleted = 0
            """;

    /**
     * 菜品查询 SQL
     */
    private String dishesBySchool = """
            SELECT id, name, type, update_time
            FROM dish_library
            WHERE school_id = #{schoolId} AND deleted = 0
            ORDER BY update_time DESC
            LIMIT 1000
            """;

    /**
     * 获取所有有菜品的学校ID
     */
    private String schoolIdsWithDishes = """
            SELECT DISTINCT school_id
            FROM dish_library
            WHERE school_id IS NOT NULL AND deleted = 0
            """;
}
