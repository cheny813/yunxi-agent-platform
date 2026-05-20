package io.yunxi.platform.business.nutrition.config;

import io.yunxi.platform.framework.profile.ConceptRegistry;
import io.yunxi.platform.framework.profile.ConceptRegistry.ConceptEntry;
import io.yunxi.platform.framework.memory.MemorySceneRegistry;
import io.yunxi.platform.framework.profile.ProfessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * 校园餐产品概念与场景注册配置
 *
 * <p>在 Spring 容器启动后，将校园餐相关的概念、职业和记忆场景注册到框架层 Registry。</p>
 * <p>校园餐产品覆盖7个业务模块：食品安全、营养配餐、经费管理、集采-校园、集采-国企、消费管理、预警</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "true")
public class SchoolMealConceptConfig {

    /** 职业注册表 */
    private final ProfessionRegistry professionRegistry;
    /** 记忆场景注册表 */
    private final MemorySceneRegistry memorySceneRegistry;
    /** 概念注册表 */
    private final ConceptRegistry conceptRegistry;

    /**
     * 构造函数
     *
     * @param professionRegistry  职业注册表
     * @param memorySceneRegistry 记忆场景注册表
     * @param conceptRegistry     概念注册表
     */
    public SchoolMealConceptConfig(ProfessionRegistry professionRegistry,
                                    MemorySceneRegistry memorySceneRegistry,
                                    ConceptRegistry conceptRegistry) {
        this.professionRegistry = professionRegistry;
        this.memorySceneRegistry = memorySceneRegistry;
        this.conceptRegistry = conceptRegistry;
    }

    /**
     * 注册校园餐产品概念与相关职业
     *
     * <p>
     * 将校园餐产品概念(SCHOOL_MEAL)以及营养师(NUTRITIONIST)、食品安全员(FOOD_SAFETY)、厨师(CHEF)等职业
     * 注册到框架层的概念注册表和职业注册表中。
     * </p>
     */
    @PostConstruct
    public void registerSchoolMealConcepts() {
        // 注册校园餐产品概念
        conceptRegistry.register(createEntry(
                "SCHOOL_MEAL", "school-meal", "校园餐",
                "校园餐、学校食堂、学生餐、幼儿园餐、配餐、食谱、营养、食品安全、集采",
                "PRODUCT"));

        // 注册校园餐相关职业
        professionRegistry.register("NUTRITIONIST", "营养师", "营养、食谱、健康、膳食、配餐");
        professionRegistry.register("FOOD_SAFETY", "食品安全员", "食品安全、卫生、检测、监管");
        professionRegistry.register("CHEF", "厨师", "烹饪、菜品、食材、厨房、调味");
        log.info("校园餐概念与职业注册完成: SCHOOL_MEAL, NUTRITIONIST, FOOD_SAFETY, CHEF");
    }

    /**
     * 注册校园餐记忆场景
     *
     * <p>
     * 将校园餐管理场景注册到记忆场景注册表，涉及食谱制作、营养配平、食品安全、经费管理、集采、消费管理、预警等业务。
     * </p>
     */
    @PostConstruct
    public void registerSchoolMealScenes() {
        // 注册校园餐记忆场景
        memorySceneRegistry.register(
                "SCHOOL_MEAL",
                "校园餐",
                "校园餐管理场景，涉及食谱制作、营养配平、食品安全、经费管理、集采、消费管理、预警等",
                -1,
                List.of("食谱", "营养", "菜品", "配餐", "配平", "膳食", "食材", "校园餐", "食堂",
                        "食品安全", "经费", "集采", "采购", "消费", "预警")
        );
        log.info("校园餐场景注册完成: SCHOOL_MEAL");
    }

    /**
     * 创建概念条目
     *
     * @param name        概念名称
     * @param domain      领域标识
     * @param displayName 显示名称
     * @param keywords    关键词（逗号分隔）
     * @param category    分类
     * @return 概念条目实例
     */
    private ConceptEntry createEntry(String name, String domain, String displayName, String keywords, String category) {
        ConceptEntry entry = new ConceptEntry();
        entry.setName(name);
        entry.setDomain(domain);
        entry.setDisplayName(displayName);
        entry.setKeywords(keywords);
        entry.setCategory(category);
        return entry;
    }
}
