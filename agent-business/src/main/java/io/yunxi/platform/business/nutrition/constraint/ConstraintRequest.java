package io.yunxi.platform.business.nutrition.constraint;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 配餐约束请求 DTO
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConstraintRequest {

    /** 学校ID (B端必填) */
    private Long schoolId;

    /** 用户ID (C端预留) */
    private Long userId;

    /** 区县编码 */
    private String districtCode;

    /** 省份编码 */
    private String provinceCode;

    /** 食谱类型: DAY(当日) / WEEK(周食谱) */
    private String mealType;
}
