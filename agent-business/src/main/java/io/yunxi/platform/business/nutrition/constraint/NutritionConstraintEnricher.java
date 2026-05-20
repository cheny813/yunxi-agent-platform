package io.yunxi.platform.business.nutrition.constraint;

import io.yunxi.platform.framework.spi.ContextEnricher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 营养约束上下文注入器 (ContextEnricher SPI)
 *
 * <p>
 * 当检测到 contextData 中包含 schoolId 时，自动调用
 * {@link DynamicConstraintService} 获取约束策略并注入到对话上下文，
 * 无需 Agent 主动调用工具。
 * </p>
 *
 * <p>
 * 通过 {@code nutrition-extension.constraint.enabled=true} 启用，
 * 默认关闭，不影响现有学校配餐流程。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "nutrition-extension.constraint.enabled", havingValue = "true")
public class NutritionConstraintEnricher implements ContextEnricher {

    /** 动态配餐约束服务，用于构建约束策略 */
    private final DynamicConstraintService constraintService;

    /**
     * 构造函数
     *
     * @param constraintService 配餐约束服务
     */
    public NutritionConstraintEnricher(DynamicConstraintService constraintService) {
        this.constraintService = constraintService;
    }

    /**
     * 判断是否支持当前上下文数据
     *
     * @param contextData 上下文数据
     * @return 当contextData中包含schoolId时返回true，否则返回false
     */
    @Override
    public boolean supports(Map<String, Object> contextData) {
        // 当 contextData 包含 schoolId 时激活
        return contextData != null && contextData.containsKey("schoolId");
    }

    /**
     * 注入营养约束策略到对话上下文
     *
     * <p>
     * 从contextData中提取schoolId、districtCode、provinceCode、mealType，
     * 调用约束服务构建策略并返回策略描述文本
     * </p>
     *
     * @param contextData 上下文数据，需包含schoolId字段
     * @param userMessage 用户消息
     * @return 约束策略描述文本，注入失败时返回空字符串
     */
    @Override
    public String enrich(Map<String, Object> contextData, String userMessage) {
        try {
            Object schoolIdObj = contextData.get("schoolId");
            if (schoolIdObj == null) {
                return "";
            }

            Long schoolId = Long.valueOf(schoolIdObj.toString());
            String districtCode = contextData.get("districtCode") != null
                    ? contextData.get("districtCode").toString()
                    : null;
            String provinceCode = contextData.get("provinceCode") != null
                    ? contextData.get("provinceCode").toString()
                    : null;
            String mealType = contextData.get("mealType") != null
                    ? contextData.get("mealType").toString()
                    : "DAY";

            ConstraintRequest request = ConstraintRequest.builder()
                    .schoolId(schoolId)
                    .districtCode(districtCode)
                    .provinceCode(provinceCode)
                    .mealType(mealType)
                    .build();

            RecommendationStrategy strategy = constraintService.buildStrategy(request);
            return strategy.getStrategyDescription();
        } catch (Exception e) {
            log.warn("营养约束上下文注入失败", e);
            return "";
        }
    }

    /**
     * 格式化键值对
     *
     * <p>
     * 约束注入器不需要特殊格式化，直接返回"key: value"格式
     * </p>
     *
     * @param key   键名
     * @param value 键值
     * @return 格式化后的字符串
     */
    @Override
    public String formatKey(String key, Object value) {
        // 约束注入器不需要格式化 key，返回默认格式
        return key + ": " + value;
    }

    /**
     * 追加额外提示词
     *
     * <p>
     * 约束策略已通过enrich()注入，无需额外追加prompt
     * </p>
     *
     * @param contextData 上下文数据
     * @return 空字符串（无需追加）
     */
    @Override
    public String appendPrompt(Map<String, Object> contextData) {
        // 约束策略已通过 enrich() 注入，无需额外追加 prompt
        return "";
    }
}
