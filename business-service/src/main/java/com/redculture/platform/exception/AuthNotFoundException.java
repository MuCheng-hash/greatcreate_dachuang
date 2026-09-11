package com.redculture.platform.exception;

/**
 * 表示认证流程依赖的账号或资源不存在。
 */
public class AuthNotFoundException extends RuntimeException {

    /**
     * 创建认证资源不存在异常。
     *
     * @param message 响应提示信息
     */
    public AuthNotFoundException(String message) {
        super(message);
    }
}
