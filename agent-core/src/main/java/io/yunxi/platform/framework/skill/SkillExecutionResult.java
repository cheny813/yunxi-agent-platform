package io.yunxi.platform.framework.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 技能执行结果
 *
 * @author yunxi-agent-platform
 */
@Data
@Builder
@AllArgsConstructor
public class SkillExecutionResult {

    /**
     * 是否执行成功
     */
    private boolean success;

    /**
     * 执行结果数据
     */
    private Object result;

    /**
     * 错误信息（失败时）
     */
    private String error;

    /**
     * 扩展信息
     */
    private Map<String, Object> metadata;

    /**
     * 创建成功结果
     */
    public static SkillExecutionResult success(Object result) {
        return SkillExecutionResult.builder()
                .success(true)
                .result(result)
                .metadata(Map.of())
                .build();
    }

    /**
     * 创建失败结果
     */
    public static SkillExecutionResult failure(String error) {
        return SkillExecutionResult.builder()
                .success(false)
                .error(error)
                .metadata(Map.of())
                .build();
    }

    /**
     * 创建带元数据的成功结果
     */
    public static SkillExecutionResult successWithMetadata(Object result, Map<String, Object> metadata) {
        return SkillExecutionResult.builder()
                .success(true)
                .result(result)
                .metadata(metadata)
                .build();
    }
}