package io.yunxi.platform.shared.dto;

import lombok.Data;

/**
 * 通用响应包装类
 * <p>
 * 提供统一的 API 响应格式，包含成功/失败状态、消息和数据
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>
 * // 成功响应
 * return CommonResponse.success(data);
 *
 * // 失败响应
 * return CommonResponse.error("错误信息");
 *
 * // 带状态码的失败响应
 * return CommonResponse.error(400, "参数错误");
 * </pre>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
public class CommonResponse<T> {

    /**
     * 响应状态码
     */
    private int code;

    /**
     * 响应消息
     */
    private String message;

    /**
     * 响应数据
     */
    private T data;

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 私有构造函数，使用静态方法创建
     */
    private CommonResponse() {
    }

    /**
     * 创建成功响应（无数据）
     *
     * @param <T> 泛型类型
     * @return 成功响应对象
     */
    public static <T> CommonResponse<T> success() {
        CommonResponse<T> response = new CommonResponse<>();
        response.setCode(200);
        response.setMessage("success");
        response.setSuccess(true);
        response.setData(null);
        return response;
    }

    /**
     * 创建成功响应（带数据）
     *
     * @param data 响应数据
     * @param <T>  泛型类型
     * @return 成功响应对象
     */
    public static <T> CommonResponse<T> success(T data) {
        CommonResponse<T> response = new CommonResponse<>();
        response.setCode(200);
        response.setMessage("success");
        response.setSuccess(true);
        response.setData(data);
        return response;
    }

    /**
     * 创建失败响应（默认状态码 500）
     *
     * @param message 错误消息
     * @param <T>     泛型类型
     * @return 失败响应对象
     */
    public static <T> CommonResponse<T> error(String message) {
        CommonResponse<T> response = new CommonResponse<>();
        response.setCode(500);
        response.setMessage(message);
        response.setSuccess(false);
        response.setData(null);
        return response;
    }

    /**
     * 创建失败响应（指定状态码）
     *
     * @param code    状态码
     * @param message 错误消息
     * @param <T>     泛型类型
     * @return 失败响应对象
     */
    public static <T> CommonResponse<T> error(int code, String message) {
        CommonResponse<T> response = new CommonResponse<>();
        response.setCode(code);
        response.setMessage(message);
        response.setSuccess(false);
        response.setData(null);
        return response;
    }

    /**
     * 创建失败响应（指定状态码和数据）
     *
     * @param code    状态码
     * @param message 错误消息
     * @param data    错误相关数据
     * @param <T>     泛型类型
     * @return 失败响应对象
     */
    public static <T> CommonResponse<T> error(int code, String message, T data) {
        CommonResponse<T> response = new CommonResponse<>();
        response.setCode(code);
        response.setMessage(message);
        response.setSuccess(false);
        response.setData(data);
        return response;
    }
}