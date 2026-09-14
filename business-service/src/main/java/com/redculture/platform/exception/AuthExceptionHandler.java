package com.redculture.platform.exception;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.controller.AuthController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将认证接口异常转换为统一 HTTP 响应。
 */
@RestControllerAdvice(assignableTypes = {AuthController.class})
public class AuthExceptionHandler {

    /**
     * 当前异常处理器使用的日志记录器。
     */
    private static final Logger log = LoggerFactory.getLogger(AuthExceptionHandler.class);

    /**
     * 处理匹配的异常并转换为统一 HTTP 响应。
     *
     * @param exception 捕获到的异常
     * @return 统一封装的 HTTP 响应
     */
    @ExceptionHandler(AuthConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(AuthConflictException exception) {
        return response(HttpStatus.CONFLICT, exception.getMessage());
    }

    /**
     * 处理匹配的异常并转换为统一 HTTP 响应。
     *
     * @param exception 捕获到的异常
     * @return 统一封装的 HTTP 响应
     */
    @ExceptionHandler(AuthNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(AuthNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    /**
     * 处理匹配的异常并转换为统一 HTTP 响应。
     *
     * @param exception 捕获到的异常
     * @return 统一封装的 HTTP 响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException exception) {
        return response(HttpStatus.BAD_REQUEST, "请求参数格式错误。");
    }

    /**
     * 处理匹配的异常并转换为统一 HTTP 响应。
     *
     * @param exception 捕获到的异常
     * @return 统一封装的 HTTP 响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(IllegalArgumentException exception) {
        return response(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    /**
     * 处理匹配的异常并转换为统一 HTTP 响应。
     *
     * @param exception 捕获到的异常
     * @return 统一封装的 HTTP 响应
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(DataIntegrityViolationException exception) {
        log.warn("Authentication request violated a database constraint", exception);
        return response(HttpStatus.CONFLICT, "账号已存在。");
    }

    /**
     * 处理匹配的异常并转换为统一 HTTP 响应。
     *
     * @param exception 捕获到的异常
     * @return 统一封装的 HTTP 响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        log.error("Authentication request failed unexpectedly", exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "认证服务暂时不可用，请稍后重试。");
    }

    private ResponseEntity<ApiResponse<Void>> response(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ApiResponse.fail(status.value(), message));
    }
}
