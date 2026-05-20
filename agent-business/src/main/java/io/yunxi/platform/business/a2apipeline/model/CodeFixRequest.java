package io.yunxi.platform.business.a2apipeline.model;

import lombok.Builder;
import lombok.Data;

/**
 * CodeFixPipeline 代码修复流水线请求
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
public class CodeFixRequest {

    /**
     * 任务ID
     */
    private String taskId;

    /**
     * 任务标题
     */
    private String title;

    /**
     * 问题描述
     */
    private String description;

    /**
     * 代码修复上下文
     */
    private CodeFixContext context;

    /**
     * 质量门禁配置
     */
    private QualityGateConfig qualityGate;

    /**
     * 通知配置
     */
    private NotificationConfig notification;

    /**
     * 创建代码修复请求
     *
     * @param title       任务标题
     * @param description 问题描述
     * @return 代码修复请求实例
     */
    public static CodeFixRequest create(String title, String description) {
        return CodeFixRequest.builder()
                .taskId(generateTaskId())
                .title(title)
                .description(description)
                .build();
    }

    /**
     * 生成任务 ID
     *
     * @return 格式为 "FIX-时间戳" 的唯一任务 ID
     */
    private static String generateTaskId() {
        return "FIX-" + System.currentTimeMillis();
    }
}