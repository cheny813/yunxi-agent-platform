package io.yunxi.platform.business.nutrition.constraint;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 配餐约束服务 REST API
 *
 * <p>
 * 提供约束策略查询接口，供前端或外部系统调用。
 * 通过 {@code nutrition-extension.constraint.enabled=true} 启用。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/api/constraint")
@ConditionalOnProperty(name = "nutrition-extension.constraint.enabled", havingValue = "true")
public class ConstraintController {

    /** 动态配餐约束服务，整合民族、菜系、季节、价格、天气等约束维度 */
    private final DynamicConstraintService constraintService;

    /**
     * 构造函数
     *
     * @param constraintService 配餐约束服务
     */
    public ConstraintController(DynamicConstraintService constraintService) {
        this.constraintService = constraintService;
    }

    /**
     * 查询学校配餐约束策略
     *
     * @param schoolId     学校ID
     * @param districtCode 区县编码（可选，用于价格模式查询）
     * @param provinceCode 省份编码（可选，用于菜系推荐）
     * @param mealType     食谱类型: DAY(当日) / WEEK(周食谱)，默认DAY
     * @return 推荐策略
     */
    @GetMapping("/strategy")
    public ResponseEntity<RecommendationStrategy> getStrategy(
            @RequestParam Long schoolId,
            @RequestParam(required = false) String districtCode,
            @RequestParam(required = false) String provinceCode,
            @RequestParam(defaultValue = "DAY") String mealType) {

        log.info("查询配餐约束策略: schoolId={}, districtCode={}, mealType={}", schoolId, districtCode, mealType);

        ConstraintRequest request = ConstraintRequest.builder()
                .schoolId(schoolId)
                .districtCode(districtCode)
                .provinceCode(provinceCode)
                .mealType(mealType)
                .build();

        RecommendationStrategy strategy = constraintService.buildStrategy(request);
        return ResponseEntity.ok(strategy);
    }

    /**
     * 查询个人咨询约束策略（预留）
     *
     * @param userId 用户ID
     * @return 推荐策略
     */
    @GetMapping("/personal-strategy")
    public ResponseEntity<RecommendationStrategy> getPersonalStrategy(
            @RequestParam Long userId) {

        log.info("查询个人咨询约束策略: userId={}", userId);
        RecommendationStrategy strategy = constraintService.getPersonalStrategy(userId);
        return ResponseEntity.ok(strategy);
    }

    /**
     * 健康检查
     *
     * @return 状态信息
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "DynamicConstraintService"));
    }
}
