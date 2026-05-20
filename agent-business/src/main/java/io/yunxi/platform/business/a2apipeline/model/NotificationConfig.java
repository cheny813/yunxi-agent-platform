package io.yunxi.platform.business.a2apipeline.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * NotificationConfig 通知配置
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
public class NotificationConfig {

    /**
     * 任务完成时通知
     */
    private boolean notifyOnComplete;

    /**
     * 通知渠道列表 (email, slack, webhook等)
     */
    private List<String> notifyChannels;

    /**
     * 通知接收人列表
     */
    private List<String> notifyRecipients;

    /**
     * 默认配置
     *
     * @return 默认的通知配置实例
     */
    public static NotificationConfig defaultConfig() {
        return NotificationConfig.builder()
                .notifyOnComplete(true)
                .notifyChannels(List.of("email"))
                .build();
    }
}