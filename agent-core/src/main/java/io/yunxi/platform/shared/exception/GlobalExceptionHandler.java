package io.yunxi.platform.shared.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
     * 处理通用异常
     *
     * @param ex Exception 异常对象
     * @return 500 INTERNAL_SERVER_ERROR 响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        log.error("服务器内部错误", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createError("INTERNAL_ERROR", ex.getMessage()));
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
