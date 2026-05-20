package io.yunxi.platform.shared.spi;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 通知服务 SPI 接口
 *
 * <p>
 * 提供 SSE（Server-Sent Events）连接管理和消息推送能力，支持：
 * <ul>
 *   <li>连接生命周期管理</li>
 *   <li>按会话/任务推送消息</li>
 *   <li>进度通知</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>架构分层</b>：此接口位于 shared 层，允许 framework/business 层通过接口访问 SSE 服务，
 * 避免直接依赖 infra 层的具体实现。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public interface SseNotificationProvider {

    /**
     * 创建并注册一个新的 SSE 连接
     *
     * @param sessionId 会话ID（唯一标识）
     * @return SseEmitter 实例
     */
    SseEmitter createEmitter(String sessionId);

    /**
     * 创建并注册一个新的 SSE 连接
     *
     * @param sessionId 会话ID
     * @param timeout   超时时间（毫秒）
     * @return SseEmitter 实例
     */
    SseEmitter createEmitter(String sessionId, long timeout);

    /**
     * 向指定会话发送消息
     *
     * @param sessionId 会话ID
     * @param eventName 事件名称
     * @param data      数据
     * @return 是否发送成功
     */
    boolean send(String sessionId, String eventName, Object data);

    /**
     * 向指定会话发送消息（带 ID）
     *
     * @param sessionId 会话ID
     * @param id        消息ID
     * @param eventName 事件名称
     * @param data      数据
     * @return 是否发送成功
     */
    boolean send(String sessionId, String id, String eventName, Object data);

    /**
     * 向指定会话发送完成事件
     *
     * @param sessionId 会话ID
     */
    void complete(String sessionId);

    /**
     * 向指定会话发送错误事件
     *
     * @param sessionId 会话ID
     * @param error     错误信息
     */
    void completeWithError(String sessionId, Throwable error);

    /**
     * 移除指定会话的连接
     *
     * @param sessionId 会话ID
     */
    void removeEmitter(String sessionId);

    /**
     * 检查会话是否有活跃连接
     *
     * @param sessionId 会话ID
     * @return 是否存在活跃连接
     */
    boolean hasEmitter(String sessionId);

    /**
     * 获取当前活跃连接数
     *
     * @return 连接数
     */
    int getActiveConnectionCount();

    /**
     * 广播消息给所有连接
     *
     * @param eventName 事件名称
     * @param data      数据
     */
    void broadcast(String eventName, Object data);

    /**
     * 注册进度监听器
     *
     * @param sessionId 会话ID
     * @param listener  进度监听器
     */
    void registerProgressListener(String sessionId, ProgressListener listener);

    /**
     * 进度监听器接口
     */
    interface ProgressListener {
        /**
         * 进度更新回调
         *
         * @param progress 进度（0-100）
         * @param message  进度消息
         */
        void onProgress(int progress, String message);

        /**
         * 完成回调
         *
         * @param result 结果数据
         */
        void onComplete(Object result);

        /**
         * 错误回调
         *
         * @param error 错误信息
         */
        void onError(String error);
    }
}
