package io.yunxi.platform.gateway.channel;

import io.yunxi.platform.gateway.model.PlatformType;

/**
 * 消息平台适配器 SPI 接口
 *
 * <p>所有消息平台适配器必须实现此接口。网关通过此接口与平台交互，
 * 新增平台只需实现此接口并注册为 Spring Bean 即可。</p>
 */
public interface MessageChannel {

    /**
     * 获取平台类型
     */
    PlatformType getPlatformType();

    /**
     * 初始化并连接平台
     *
     * @param handler 消息接收回调（MessageContext + 消息文本）
     */
    void connect(java.util.function.BiConsumer<MessageContext, String> handler);

    /**
     * 断开连接
     */
    void disconnect();

    /**
     * 是否已连接
     */
    boolean isConnected();

    /**
     * 发送文本消息
     *
     * @param chatId  平台会话 ID
     * @param text    消息文本
     * @param context 消息上下文
     */
    void sendTextMessage(String chatId, String text, MessageContext context);

    /**
     * 发送 Markdown 富文本消息
     *
     * <p>如果平台不支持 Markdown，应降级为纯文本</p>
     *
     * @param chatId   平台会话 ID
     * @param markdown Markdown 内容
     * @param context  消息上下文
     */
    default void sendMarkdownMessage(String chatId, String markdown, MessageContext context) {
        sendTextMessage(chatId, markdown, context);
    }

    /**
     * 发送"思考中"状态指示
     *
     * @param chatId  平台会话 ID
     * @param context 消息上下文
     */
    default void sendTypingIndicator(String chatId, MessageContext context) {
        // 默认不实现，平台可按需覆盖
    }

    /**
     * 健康检查
     */
    ChannelHealth health();
}
