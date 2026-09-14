package com.redculture.platform.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 封装接口统一响应码、提示信息和业务数据。
 *
 * @param <T> 响应业务数据类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    /**
     * 业务响应码；成功响应使用 {@code 200}，失败响应由调用方指定或默认使用 {@code 500}。
     */
    private Integer code;

    /**
     * 面向调用方的响应提示信息。
     */
    private String message;

    /**
     * 响应携带的业务数据；失败响应通常为 {@code null}。
     */
    private T data;

    /**
     * 创建成功响应。
     *
     * @param <T> 返回数据类型
     * @param data 业务数据
     * @return 成功响应对象
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "success", data);
    }

    /**
     * 创建成功响应。
     *
     * @param <T> 返回数据类型
     * @param message 响应提示信息
     * @param data 业务数据
     * @return 成功响应对象
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(200, message, data);
    }

    /**
     * 创建失败响应。
     *
     * @param <T> 返回数据类型
     * @param message 响应提示信息
     * @return 失败响应对象
     */
    public static <T> ApiResponse<T> fail(String message) {
        return fail(500, message);
    }

    /**
     * 创建失败响应。
     *
     * @param <T> 返回数据类型
     * @param code 业务响应码
     * @param message 响应提示信息
     * @return 失败响应对象
     */
    public static <T> ApiResponse<T> fail(Integer code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
