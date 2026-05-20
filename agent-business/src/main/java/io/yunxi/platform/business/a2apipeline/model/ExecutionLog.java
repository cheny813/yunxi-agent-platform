package io.yunxi.platform.business.a2apipeline.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * ExecutionLog 执行日志
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
public class ExecutionLog {

    /**
     * 时间戳
     */
    private Instant timestamp;

    /**
     * 日志级别 (INFO, WARN, ERROR, DEBUG)
     */
    private String level;

    /**
     * 日志消息
     */
    private String message;

    /**
     * 来源组件
     */
    private String source;

    /**
     * 创建信息日志
     *
     * @param message 日志消息
     * @return INFO 级别的执行日志实例
     */
    public static ExecutionLog info(String message) {
        return ExecutionLog.builder()
                .timestamp(Instant.now())
                .level("INFO")
                .message(message)
                .build();
    }

    /**
     * 创建错误日志
     *
     * @param message 日志消息
     * @return ERROR 级别的执行日志实例
     */
    public static ExecutionLog error(String message) {
        return ExecutionLog.builder()
                .timestamp(Instant.now())
                .level("ERROR")
                .message(message)
                .build();
    }
}