package io.yunxi.platform.shared.exception;

/**
 * 资源未找到异常
 *
 * @author yunxi-agent-platform
 */
public class NotFoundException extends RuntimeException {

    /**
     * 构造资源未找到异常
     *
     * @param message 错误信息
     */
    public NotFoundException(String message) {

        super(message);

    }

    /**
     * 构造资源未找到异常
     *
     * @param message 错误信息
     * @param cause   原始异常
     */
    public NotFoundException(String message, Throwable cause) {

        super(message, cause);

    }

}
