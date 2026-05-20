package io.yunxi.platform.business.a2apipeline.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * ReviewOutput 审查输出
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
public class ReviewOutput {

    /**
     * 审查是否通过
     */
    private boolean approved;

    /**
     * 问题列表
     */
    private List<String> issues;

    /**
     * 建议列表
     */
    private List<String> suggestions;

    /**
     * 代码质量分数
     */
    private int qualityScore;

    /**
     * 创建批准输出
     *
     * @return 审查批准的输出实例
     */
    public static ReviewOutput approved() {
        return ReviewOutput.builder()
                .approved(true)
                .qualityScore(100)
                .build();
    }

    /**
     * 创建拒绝输出
     *
     * @param issues 问题列表
     * @return 审查拒绝的输出实例
     */
    public static ReviewOutput rejected(List<String> issues) {
        return ReviewOutput.builder()
                .approved(false)
                .issues(issues)
                .qualityScore(0)
                .build();
    }
}