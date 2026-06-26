package io.yunxi.platform.shared.exception;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理资源未找到异常
     *
     * @param ex NotFoundException 异常对象
     * @return 404 NOT_FOUND 响应
     */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(createError("NOT_FOUND", ex.getMessage()));
    }

    /**
     * 处理错误请求异常
     *
     * @param ex BadRequestException 异常对象
     * @return 400 BAD_REQUEST 响应
     */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(BadRequestException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(createError("BAD_REQUEST", ex.getMessage()));
    }

    /**
     * 处理静态资源未找到异常
     * <p>
     * 当请求的静态资源不存在时，返回404状态码而不是500内部错误
     * 这有助于前端正确区分API失败和资源不存在的情况
     * </p>
     *
     * @param ex NoResourceFoundException 异常对象
     * @return 404 NOT_FOUND 响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResourceFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(createError("NOT_FOUND", ex.getMessage()));
    }

    /**
     * 处理参数校验异常
     *
     * @param ex MethodArgumentNotValidException 校验异常对象
     * @return 400 BAD_REQUEST 响应，包含字段校验错误详情
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();

        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.put(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(createError("VALIDATION_ERROR", "参数校验失败", createDetailsMap(errors)));
    }

    /**
     * 处理客户端断开连接异常（SSE 流被中止时）
     * <p>
     * 用户停止流式推理或关闭页面时触发，属于正常行为，不记录 ERROR。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientAbort(AsyncRequestNotUsableException ex) {
        log.debug("客户端断开连接（SSE 流中止）: {}", ex.getMessage());
    }

    /**
     * 处理通用异常
     * <p>
     * 如果响应已设置为 {@code text/event-stream}（SSE 流），则直接写入 SSE 格式的错误事件，
     * 避免 Spring 因无法序列化 {@code ResponseEntity} 而抛出
     * {@code HttpMessageNotWritableException}。
     * </p>
     *
     * @param ex       Exception 异常对象
     * @param response HTTP 响应（用于检测 SSE Content-Type）
     * @return 内部错误响应，SSE 流时返回 null（已手动写入响应）
     */
    @ExceptionHandler(Exception.class)
    public Object handleGeneral(Exception ex, HttpServletResponse response) {
        log.error("服务器内部错误", ex);

        // SSE 流感知：当响应已设置为 text/event-stream 时，直接写入 SSE 格式错误事件
        if (response.getContentType() != null
                && response.getContentType().contains("text/event-stream")) {
            try {
                String errorJson = "{\"type\":\"error\",\"message\":\""
                        + escapeJson(ex.getMessage()) + "\"}";
                response.getWriter().write("data: " + errorJson + "\n\n");
                response.getWriter().flush();
                log.warn("SSE 流异常已写为 SSE error 事件");
                return null;
            } catch (IOException ioEx) {
                log.error("SSE 错误写入失败", ioEx);
            }
        }

        // 对 Path 文件系统类型不匹配异常做友好提示
        String message = ex.getMessage();
        if (ex instanceof IllegalArgumentException
                && message != null
                && message.contains("different type of Path")) {
            log.warn("检测到 Path 文件系统类型不匹配。"
                    + "可尝试设置 agentscope.skill-box.enabled=false", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createError("INTERNAL_ERROR", "服务内部错误，请联系管理员"));
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createError("INTERNAL_ERROR", ex.getMessage()));
    }

    /**
     * 转义 JSON 字符串中的特殊字符，用于 SSE 错误消息的 JSON 体。
     *
     * @param str 原始字符串
     * @return 转义后的字符串
     */
    private String escapeJson(String str) {
        if (str == null)
            return "";
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 创建错误响应体
     *
     * @param code    错误码
     * @param message 错误信息
     * @return 包含错误码、信息和时间戳的映射对象
     */
    private Map<String, Object> createError(String code, String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("timestamp", Instant.now().toString());
        return error;
    }

    /**
     * 创建带详细信息的错误响应体
     *
     * @param code    错误码
     * @param message 错误信息
     * @param details 详细信息映射
     * @return 包含错误码、信息、时间戳和详情的映射对象
     */
    private Map<String, Object> createError(String code, String message, Map<String, Object> details) {
        Map<String, Object> error = createError(code, message);
        error.putAll(details);
        return error;
    }

    /**
     * 创建验证错误的详细信息映射
     * <p>
     * 由于Java版本兼容性考虑，使用HashMap代替Map.of()方法
     * </p>
     *
     * @param errors 字段验证错误映射
     * @return 包含错误详情的映射对象
     */
    private Map<String, Object> createDetailsMap(Map<String, String> errors) {
        Map<String, Object> details = new HashMap<>();
        details.put("details", errors);
        return details;
    }
}
