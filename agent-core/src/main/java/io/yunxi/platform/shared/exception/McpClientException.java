package io.yunxi.platform.shared.exception;

/**
 * MCP 客户端异常
 * <p>
 * 用于封装 MCP 客户端调用过程中的异常情况
 * </p>
 *
 */
public class McpClientException extends RuntimeException {

    /**
     * 构造 MCP 客户端异常
     *
     * @param message 错误信息
     */
    public McpClientException(String message) {
        super(message);
    }

    /**
     * 构造 MCP 客户端异常
     *
     * @param message 错误信息
     * @param cause   原始异常
     */
    public McpClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
