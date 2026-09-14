package com.redculture.platform.exception;

/**
 * 表示认证或账号资源与现有数据发生冲突。
 */
public class AuthConflictException extends RuntimeException {

    /**
     * 创建认证资源冲突异常。
     *
     * @param message 响应提示信息
     */
    public AuthConflictException(String message) {
        super(message);
    }
}
