package com.redculture.platform.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 携带 HTTP 状态和业务错误码的教学方案反馈异常。
 */
@Getter
public class TeachingPlanFeedbackException extends RuntimeException {
    /**
     * 该业务异常应转换成的 HTTP 状态码。
     */
    private final HttpStatus status;
    /**
     * 返回给调用方的稳定业务错误码。
     */
    private final String code;

    /**
     * 创建教学方案反馈异常。
     *
     * @param status HTTP 状态码
     * @param code 教学方案反馈业务错误码
     * @param message 响应提示信息
     */
    public TeachingPlanFeedbackException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
