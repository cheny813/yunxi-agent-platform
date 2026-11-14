package io.yunxi.app.tenant;

import io.yunxi.runtime.tenant.QuotaExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 租户配额超限异常统一映射：{@link QuotaExceededException} → HTTP 429（Too Many Requests）。
 * 单一映射点（胶水指数 1），覆盖四协议（web / A2A / AG-UI / Chat Completions）。
 *
 * <p>注意：流式（SSE）响应在错误发生前已发出 200 头部，429 无法回填；此时 Agent 已被闸门拦截不执行，
 * 仅错误事件无法改写为 429 状态码——非流式与同步入口可正常收到 429。
 */
@ControllerAdvice
public class TenantQuotaExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(TenantQuotaExceptionHandler.class);

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<Map<String, Object>> handle(QuotaExceededException e) {
        log.warn("[TenantQuota] 租户配额超限：tenant={} type={} limit={}", e.getTenantId(), e.getType(), e.getLimit());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "quota_exceeded");
        body.put("tenant", e.getTenantId());
        body.put("type", e.getType().name());
        body.put("limit", e.getLimit());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }
}
