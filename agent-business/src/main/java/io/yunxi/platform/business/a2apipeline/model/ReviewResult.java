package io.yunxi.platform.business.a2apipeline.model;

import lombok.Builder;
import lombok.Data;

/**
 * ReviewResult 审查结果
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
public class ReviewResult {

    /**
     * 是否通过
     */
    private boolean passed;

    /**
     * 发现的问题数
     */
    private int issueCount;

    /**
     * 问题严重程度 (LOW, MEDIUM, HIGH, CRITICAL)
     */
    private String highestSeverity;

    /**
     * 审查意见
     */
    private String comment;

    /**
     * 需要修复的文件列表
     */
    private java.util.List<String> filesToFix;

    /**
     * 创建通过结果
     *
     * @return 审查通过的审查结果实例
     */
    public static ReviewResult passed() {
        return ReviewResult.builder()
                .passed(true)
                .issueCount(0)
                .highestSeverity("NONE")
                .build();
    }

    /**
     * 创建未通过结果
     *
     * @param issueCount       发现的问题数
     * @param highestSeverity  最高问题严重程度
     * @return 审查未通过的审查结果实例
     */
    public static ReviewResult failed(int issueCount, String highestSeverity) {
        return ReviewResult.builder()
                .passed(false)
                .issueCount(issueCount)
                .highestSeverity(highestSeverity)
                .build();
    }
}