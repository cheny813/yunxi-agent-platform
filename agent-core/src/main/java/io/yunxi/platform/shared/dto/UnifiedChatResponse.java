package io.yunxi.platform.shared.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * 统一对话响应 DTO
 *
 * <p>
 * 这是统一对话接口的响应对象，根据不同的模式和执行情况，
 * 返回的内容会有所不同。
 * </p>
 *
 * <p>
 * <b>同步模式响应</b>:
 * 
 * <pre>
 * {
 *   "success": true,
 *   "conversationId": "conv-xxx",
 *   "response": "这是完整的回复内容",
 *   "model": "qwen-plus",
 *   "tokens": {
 *     "input": 10,
 *     "output": 50,
 *     "total": 60
 *   },
 *   "duration": 1234,
 *   "timestamp": "2026-02-09T10:00:00Z"
 * }
 * </pre>
 * </p>
 *
 * <p>
 * <b>异步模式响应</b>:
 * 
 * <pre>
 * {
 *   "success": true,
 *   "taskId": "task-xxx",
 *   "status": "pending",
 *   "message": "任务已创建，请使用 /async/tasks/{taskId} 查询状态",
 *   "timestamp": "2026-02-09T10:00:00Z"
 * }
 * </pre>
 * </p>
 *
 * <p>
 * <b>结构化输出响应</b>:
 * 
 * <pre>
 * {
 *   "success": true,
 *   "conversationId": "conv-xxx",
 *   "data": {
 *     "name": "张三",
 *     "age": 30,
 *     "email": "zhangsan@example.com"
 *   },
 *   "model": "qwen-plus",
 *   "timestamp": "2026-02-09T10:00:00Z"
 * }
 * </pre>
 * </p>
 *
 * <p>
 * <b>错误响应</b>:
 * 
 * <pre>
 * {
 *   "success": false,
 *   "error": {
 *     "code": "AGENT_NOT_FOUND",
 *     "message": "Agent 不存在: xxx",
 *     "details": {...}
 *   },
 *   "timestamp": "2026-02-09T10:00:00Z"
 * }
 * </pre>
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UnifiedChatResponse {

    // ==================== 成功标识 ====================

    /**
     * 是否成功（必填）
     */
    private Boolean success;

    // ==================== 对话结果（同步模式） ====================

    /**
     * Agent 的回复文本（同步模式）
     */
    private String response;

    /**
     * 结构化输出数据（结构化模式）
     */
    private Object data;

    /**
     * 会话 ID（如果涉及会话）
     */
    private String conversationId;

    /**
     * 使用的模型名称
     */
    private String model;

    /**
     * Token 使用统计
     */
    private TokenUsage tokens;

    /**
     * 执行耗时（毫秒）
     */
    private Long duration;

    // ==================== 异步任务信息（异步模式） ====================

    /**
     * 任务 ID（异步模式）
     */
    private String taskId;

    /**
     * 任务状态（异步模式）
     */
    private String status;

    /**
     * 任务消息（异步模式）
     */
    private String message;

    // ==================== 流式响应标识 ====================

    /**
     * 是否为流式响应
     */
    private Boolean stream;

    /**
     * 流式响应的 SSE 端点（如果需要）
     */
    private String streamUrl;

    // ==================== 错误信息（失败时） ====================

    /**
     * 错误信息（失败时）
     */
    private ErrorInfo error;

    // ==================== 时间戳 ====================

    /**
     * 响应时间戳（必填）
     */
    private Instant timestamp;

    // ==================== 额外信息 ====================

    /**
     * Agent 信息（可选）
     */
    private AgentInfoDto agent;

    /**
     * 使用的工具列表（如果有）
     */
    private Map<String, Object> toolsUsed;

    /**
     * 调试信息（开发环境）
     */
    private Map<String, Object> debug;

    /**
     * 配置警告信息（如果有静态配置建议）
     * <p>
     * 当请求中包含需要在 Agent 创建时配置的功能时，
     * 系统会返回配置建议，提示调用者创建专用 Agent 实例。
     * </p>
     */
    private Map<String, String> configWarnings;

    // ==================== 内部类 ====================

    /**
     * Token 使用统计
     *
     * @author yunxi-agent-platform
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TokenUsage {
        /**
         * 输入 Token 数
         */
        private Integer input;

        /**
         * 输出 Token 数
         */
        private Integer output;

        /**
         * 总 Token 数
         */
        private Integer total;

        /**
         * 默认构造函数
         */
        public TokenUsage() {
        }

        /**
         * 带参构造函数
         *
         * @param input  输入 Token 数
         * @param output 输出 Token 数
         */
        public TokenUsage(Integer input, Integer output) {
            this.input = input;
            this.output = output;
            this.total = input + output;
        }
    }

    /**
     * 错误信息
     *
     * @author yunxi-agent-platform
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorInfo {
        /**
         * 错误代码
         */
        private String code;

        /**
         * 错误消息
         */
        private String message;

        /**
         * 详细信息（可选）
         */
        private Map<String, Object> details;

        /**
         * 默认构造函数
         */
        public ErrorInfo() {
        }

        /**
         * 带参构造函数
         *
         * @param code    错误代码
         * @param message 错误消息
         */
        public ErrorInfo(String code, String message) {
            this.code = code;
            this.message = message;
        }

        /**
         * 带详细信息构造函数
         *
         * @param code    错误代码
         * @param message 错误消息
         * @param details 详细信息
         */
        public ErrorInfo(String code, String message, Map<String, Object> details) {
            this.code = code;
            this.message = message;
            this.details = details;
        }
    }

    /**
     * Agent 信息
     *
     * @author yunxi-agent-platform
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AgentInfoDto {
        /**
         * Agent 名称
         */
        private String name;

        /**
         * Agent 描述
         */
        private String description;

        /**
         * 使用的模型
         */
        private String model;

        /**
         * 默认构造函数
         */
        public AgentInfoDto() {
        }

        /**
         * 带参构造函数
         *
         * @param name  Agent 名称
         * @param model 使用的模型
         */
        public AgentInfoDto(String name, String model) {
            this.name = name;
            this.model = model;
        }
    }

    // ==================== 静态工厂方法 ====================

    /**
     * 创建成功响应（同步模式）
     *
     * @param response       回复内容
     * @param conversationId 会话 ID
     * @return 成功响应对象
     */
    public static UnifiedChatResponse success(String response, String conversationId) {
        UnifiedChatResponse resp = new UnifiedChatResponse();
        resp.setSuccess(true);
        resp.setResponse(response);
        resp.setConversationId(conversationId);
        resp.setTimestamp(Instant.now());
        return resp;
    }

    /**
     * 创建异步任务响应
     *
     * @param taskId 任务 ID
     * @param status 任务状态
     * @return 异步任务响应对象
     */
    public static UnifiedChatResponse asyncTask(String taskId, String status) {
        UnifiedChatResponse resp = new UnifiedChatResponse();
        resp.setSuccess(true);
        resp.setTaskId(taskId);
        resp.setStatus(status);
        resp.setMessage("任务已创建，请使用 /async/tasks/" + taskId + " 查询状态");
        resp.setTimestamp(Instant.now());
        return resp;
    }

    /**
     * 创建错误响应
     *
     * @param code    错误代码
     * @param message 错误消息
     * @return 错误响应对象
     */
    public static UnifiedChatResponse error(String code, String message) {
        UnifiedChatResponse resp = new UnifiedChatResponse();
        resp.setSuccess(false);
        resp.setError(new ErrorInfo(code, message));
        resp.setTimestamp(Instant.now());
        return resp;
    }

    /**
     * 创建错误响应（带详细信息）
     *
     * @param code    错误代码
     * @param message 错误消息
     * @param details 详细信息
     * @return 错误响应对象
     */
    public static UnifiedChatResponse error(String code, String message, Map<String, Object> details) {
        UnifiedChatResponse resp = new UnifiedChatResponse();
        resp.setSuccess(false);
        resp.setError(new ErrorInfo(code, message, details));
        resp.setTimestamp(Instant.now());
        return resp;
    }

    /**
     * 创建流式响应
     *
     * @param conversationId 会话 ID
     * @param streamUrl      流式端点 URL
     * @return 流式响应对象
     */
    public static UnifiedChatResponse stream(String conversationId, String streamUrl) {
        UnifiedChatResponse resp = new UnifiedChatResponse();
        resp.setSuccess(true);
        resp.setConversationId(conversationId);
        resp.setStream(true);
        resp.setStreamUrl(streamUrl);
        resp.setTimestamp(Instant.now());
        return resp;
    }
}
