package io.yunxi.platform.shared.exception;

/**
 * 错误请求异常
 *
 * @author yunxi-agent-platform
 */
public class BadRequestException extends RuntimeException {

    /**
     * 构造错误请求异常
     *
     * @param message 错误信息
     */
    public BadRequestException(String message) {

        super(message);

    }

    /**
     * 构造错误请求异常
     *
     * @param message 错误信息
     * @param cause   原始异常
     */
    public BadRequestException(String message, Throwable cause) {

        super(message, cause);

    }

}
