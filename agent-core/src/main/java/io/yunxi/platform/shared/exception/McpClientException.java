package io.yunxi.platform.shared.exception;

/**
 * MCP 瀹㈡埛绔紓甯?
 * <p>
 * 用于灏佽 MCP 瀹㈡埛绔皟鐢ㄨ繃绋嬩腑鐨勫紓甯告儏鍐?
 * </p>
 *
 */
public class McpClientException extends RuntimeException {

    /**
     * 构€?MCP 瀹㈡埛绔紓甯?
     *
     * @param message 错误信息
     */
    public McpClientException(String message) {
        super(message);
    }

    /**
     * 构€?MCP 瀹㈡埛绔紓甯?
     *
     * @param message 错误信息
     * @param cause   鍘熷异常
     */
    public McpClientException(String message, Throwable cause) {
        super(message, cause);
    }
}