package io.yunxi.platform.desktop.command;

import io.yunxi.platform.desktop.relay.DesktopRelayHandler.CommandResultEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 桌面指令-响应状态机（内核领域服务）。
 *
 * <p>承接原 {@code DesktopController} 在 HTTP 层自建的 pending/result 映射与命令结果回填，
 * 使"指令-响应状态机"成为 agent-core 的单一领域能力；HTTP 层只做传输 framing 与身份校验。
 * 依赖方向保持单向：agent-ingress 依赖本服务，本服务不反向依赖 agent-ingress。</p>
 *
 * <p>结果回填由 {@link #onCommandResultEvent(CommandResultEvent)} 监听桌面中继发布的命令结果事件自动触发；
 * 同 JVM 内的事件本地传播即可唤醒等待中的同步 future，无需跨进程协调。</p>
 */
@Slf4j
@Service
public class DesktopCommandService {

    /** 同步命令等待中的 future: requestId -> CompletableFuture */
    private final Map<String, CompletableFuture<Map<String, Object>>> pendingResults = new ConcurrentHashMap<>();

    /** 异步/已完成的命令结果缓存: requestId -> CommandResult（带过期时间） */
    private final Map<String, CommandResult> resultCache = new ConcurrentHashMap<>();

    /** 同步命令默认等待超时（秒） */
    public static final long DEFAULT_SYNC_TIMEOUT_SECONDS = 30;

    /** 结果缓存保留时长（毫秒） */
    public static final long RESULT_CACHE_TTL_MS = 60_000;

    /**
     * 监听桌面中继发布的命令结果事件，回填等待中的同步 future。
     *
     * @param event 命令结果事件（含 requestId 与结果）
     */
    @EventListener
    public void onCommandResultEvent(CommandResultEvent event) {
        CompletableFuture<Map<String, Object>> future = pendingResults.get(event.getRequestId());
        if (future != null) {
            future.complete(event.getResult());
        }
    }

    /**
     * 注册一个同步等待的 future（命令发送前调用），返回供调用方在发送后 await。
     *
     * @param requestId 请求唯一标识
     * @return 等待中的 future
     */
    public CompletableFuture<Map<String, Object>> registerSync(String requestId) {
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pendingResults.put(requestId, future);
        return future;
    }

    /**
     * 直接回填命令结果（供非事件路径或外部调用；事件路径由 {@link #onCommandResultEvent} 自动触发）。
     *
     * @param requestId 请求唯一标识
     * @param result    客户端返回的结果
     */
    public void complete(String requestId, Map<String, Object> result) {
        CompletableFuture<Map<String, Object>> future = pendingResults.get(requestId);
        if (future != null) {
            future.complete(result);
        }
    }

    /**
     * 同步等待命令结果（默认 {@link #DEFAULT_SYNC_TIMEOUT_SECONDS} 秒超时），返回标准 HTTP 响应体。
     *
     * <p>超时或异常时返回 {@code error=TIMEOUT} 的响应体，由调用方据此决定 HTTP 504。</p>
     *
     * @param requestId 请求唯一标识
     * @param future    由 {@link #registerSync} 返回的 future
     * @return 标准响应体（成功含 success/requestId，超时含 error=TIMEOUT）
     */
    public Map<String, Object> awaitSync(String requestId, CompletableFuture<Map<String, Object>> future) {
        try {
            Map<String, Object> result = future.get(DEFAULT_SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            resultCache.put(requestId, new CommandResult(result, System.currentTimeMillis() + RESULT_CACHE_TTL_MS));
            pendingResults.remove(requestId);

            Map<String, Object> response = new HashMap<>(result);
            response.put("success", "success".equals(result.get("status")));
            response.put("requestId", requestId);
            return response;
        } catch (Exception e) {
            pendingResults.remove(requestId);
            log.error("命令执行超时或失败: requestId={}, error={}", requestId, e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "TIMEOUT");
            error.put("message", "命令执行超时: " + e.getMessage());
            error.put("requestId", requestId);
            return error;
        }
    }

    /**
     * 轮询命令结果（供异步命令提交后拉取，或同步命令完成后的结果查询）。
     *
     * @param requestId 请求唯一标识
     * @return 执行中返回 PENDING；完成/缓存命中返回 COMPLETED；否则返回 ERROR
     */
    public Map<String, Object> poll(String requestId) {
        CompletableFuture<Map<String, Object>> future = pendingResults.get(requestId);
        if (future != null) {
            if (future.isDone() && !future.isCompletedExceptionally()) {
                try {
                    Map<String, Object> result = future.getNow(null);
                    pendingResults.remove(requestId);
                    if (result != null) {
                        resultCache.put(requestId, new CommandResult(result, System.currentTimeMillis() + RESULT_CACHE_TTL_MS));
                    }
                    return buildResultResponse(requestId, result);
                } catch (Exception e) {
                    pendingResults.remove(requestId);
                }
            } else if (future.isCompletedExceptionally()) {
                pendingResults.remove(requestId);
                return buildErrorResponse(requestId, "执行失败");
            }
            Map<String, Object> response = new HashMap<>();
            response.put("status", "PENDING");
            response.put("requestId", requestId);
            return response;
        }

        CommandResult cached = resultCache.get(requestId);
        if (cached != null) {
            if (cached.isExpired()) {
                resultCache.remove(requestId);
                return buildErrorResponse(requestId, "结果已过期");
            }
            return buildResultResponse(requestId, cached.getResult());
        }

        return buildErrorResponse(requestId, "请求不存在或已过期");
    }

    /**
     * 当前等待中的请求数（供状态查询）。
     *
     * @return pending 数量
     */
    public int pendingCount() {
        return pendingResults.size();
    }

    private Map<String, Object> buildResultResponse(String requestId, Map<String, Object> result) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "COMPLETED");
        response.put("requestId", requestId);

        if (result != null) {
            response.put("success", "success".equals(result.get("status")));
            response.put("result", result);
        }

        return response;
    }

    private Map<String, Object> buildErrorResponse(String requestId, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "ERROR");
        response.put("requestId", requestId);
        response.put("message", message);
        return response;
    }

    /**
     * 命令结果封装（带过期时间）。
     */
    @lombok.Data
    public static class CommandResult {
        private final Map<String, Object> result;
        private final long expireTime;

        public CommandResult(Map<String, Object> result, long expireTime) {
            this.result = result;
            this.expireTime = expireTime;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expireTime;
        }
    }
}
