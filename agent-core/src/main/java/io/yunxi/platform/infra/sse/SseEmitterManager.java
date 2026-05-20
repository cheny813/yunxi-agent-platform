package io.yunxi.platform.infra.sse;

import io.yunxi.platform.shared.spi.SseNotificationProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE Emitter 管理器
 *
 * <p>
 * 管理 SSE 连接的生命周期，支持按会话/任务ID管理多个连接
 * </p>
 *
 * <h3>特性：</h3>
 * <ul>
 * <li>连接自动清理（超时/完成/错误）</li>
 * <li>支持向特定会话推送消息</li>
 * <li>支持广播消息</li>
 * </ul>
 *
 * <p>
 * <b>架构分层</b>：实现 {@link SseNotificationProvider} SPI 接口，允许上层通过接口访问
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Component
public class SseEmitterManager implements SseNotificationProvider {

    /**
     * 会话ID -> SseEmitter 映射
     */
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * 默认超时时间：30分钟
     */
    private static final long DEFAULT_TIMEOUT = 30 * 60 * 1000L;

    /**
     * 创建并注册一个新的 SSE 连接
     *
     * @param sessionId 会话ID（唯一标识）
     * @return SseEmitter 实例
     */
    @Override
    public SseEmitter createEmitter(String sessionId) {
        return createEmitter(sessionId, DEFAULT_TIMEOUT);
    }

    /**
     * 创建并注册一个新的 SSE 连接
     *
     * @param sessionId 会话ID
     * @param timeout   超时时间（毫秒）
     * @return SseEmitter 实例
     */
    @Override
    public SseEmitter createEmitter(String sessionId, long timeout) {
        // 如果已存在，先关闭旧连接
        removeEmitter(sessionId);

        SseEmitter emitter = new SseEmitter(timeout);

        // 注册回调
        emitter.onCompletion(() -> {
            log.debug("SSE 连接完成: {}", sessionId);
            emitters.remove(sessionId);
        });

        emitter.onTimeout(() -> {
            log.debug("SSE 连接超时: {}", sessionId);
            emitters.remove(sessionId);
        });

        emitter.onError(throwable -> {
            log.debug("SSE 连接错误: {}, error: {}", sessionId, throwable.getMessage());
            emitters.remove(sessionId);
        });

        emitters.put(sessionId, emitter);
        log.info("SSE 连接已创建: {}, 当前连接数: {}", sessionId, emitters.size());

        return emitter;
    }

    /**
     * 向指定会话发送事件
     *
     * @param sessionId 会话ID
     * @param eventName 事件名称
     * @param data      数据对象
     * @return 是否发送成功
     */
    @Override
    public boolean send(String sessionId, String eventName, Object data) {
        return sendEvent(sessionId, eventName, data);
    }

    /**
     * 向指定会话发送事件（带 ID）
     *
     * @param sessionId 会话ID
     * @param id        消息ID
     * @param eventName 事件名称
     * @param data      数据对象
     * @return 是否发送成功
     */
    @Override
    public boolean send(String sessionId, String id, String eventName, Object data) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter == null) {
            log.warn("SSE 连接不存在: {}", sessionId);
            return false;
        }

        try {
            emitter.send(SseEmitter.event()
                    .id(id)
                    .name(eventName)
                    .data(data));
            return true;
        } catch (IOException e) {
            log.error("发送 SSE 事件失败: sessionId={}, event={}", sessionId, eventName, e);
            removeEmitter(sessionId);
            return false;
        }
    }

    /**
     * 向指定会话发送事件（内部方法）
     *
     * @param sessionId 会话ID
     * @param eventName 事件名称
     * @param data      数据对象
     * @return 是否发送成功
     */
    public boolean sendEvent(String sessionId, String eventName, Object data) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter == null) {
            log.warn("SSE 连接不存在: {}", sessionId);
            return false;
        }

        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(data));
            return true;
        } catch (IOException e) {
            log.error("发送 SSE 事件失败: sessionId={}, event={}", sessionId, eventName, e);
            removeEmitter(sessionId);
            return false;
        }
    }

    /**
     * 向指定会话发送普通消息（无事件名）
     *
     * @param sessionId 会话ID
     * @param data      数据对象
     * @return 是否发送成功
     */
    public boolean send(String sessionId, Object data) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter == null) {
            log.warn("SSE 连接不存在: {}", sessionId);
            return false;
        }

        try {
            emitter.send(data);
            return true;
        } catch (IOException e) {
            log.error("发送 SSE 消息失败: sessionId={}", sessionId, e);
            removeEmitter(sessionId);
            return false;
        }
    }

    /**
     * 完成指定会话的 SSE 连接
     *
     * @param sessionId 会话ID
     */
    @Override
    public void complete(String sessionId) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception e) {
                log.debug("完成 SSE 连接异常: {}", sessionId);
            }
            emitters.remove(sessionId);
        }
    }

    /**
     * 完成指定会话的 SSE 连接并返回错误
     *
     * @param sessionId 会话ID
     * @param error     错误信息
     */
    @Override
    public void completeWithError(String sessionId, Throwable error) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter != null) {
            try {
                emitter.completeWithError(error);
            } catch (Exception e) {
                log.debug("完成 SSE 连接（错误）异常: {}", sessionId);
            }
            emitters.remove(sessionId);
        }
    }

    /**
     * 移除 SSE 连接
     *
     * @param sessionId 会话ID
     */
    @Override
    public void removeEmitter(String sessionId) {
        SseEmitter emitter = emitters.remove(sessionId);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 检查连接是否存在
     *
     * @param sessionId 会话ID
     * @return 是否存在
     */
    @Override
    public boolean hasEmitter(String sessionId) {
        return emitters.containsKey(sessionId);
    }

    /**
     * 获取当前连接数
     *
     * @return 连接数
     */
    @Override
    public int getActiveConnectionCount() {
        return emitters.size();
    }

    /**
     * 广播消息给所有连接
     *
     * @param eventName 事件名称
     * @param data      数据
     */
    @Override
    public void broadcast(String eventName, Object data) {
        emitters.keySet().forEach(sessionId -> send(sessionId, eventName, data));
    }

    /**
     * 注册进度监听器
     *
     * @param sessionId 会话ID
     * @param listener  进度监听器
     */
    @Override
    public void registerProgressListener(String sessionId, ProgressListener listener) {
        // 通过发送特定事件来注册监听器状态
        send(sessionId, "progress-registered", Map.of("sessionId", sessionId));
    }
}
