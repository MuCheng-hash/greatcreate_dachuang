package com.redculture.platform.exception;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.controller.AuthController;
import com.redculture.platform.controller.SchoolRegistrationAdminController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {AuthController.class, SchoolRegistrationAdminController.class})
public class AuthExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthExceptionHandler.class);

    @ExceptionHandler(AuthConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(AuthConflictException exception) {
        return response(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(AuthNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(AuthNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException exception) {
        return response(HttpStatus.BAD_REQUEST, "请求参数格式错误，请检查学校学段、办学性质和地理信息。");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(IllegalArgumentException exception) {
        return response(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(DataIntegrityViolationException exception) {
        log.warn("Authentication request violated a database constraint", exception);
        return response(HttpStatus.CONFLICT, "账号已存在或注册申请已提交。");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        log.error("Authentication request failed unexpectedly", exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "认证服务暂时不可用，请稍后重试。");
    }

    private ResponseEntity<ApiResponse<Void>> response(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ApiResponse.fail(status.value(), message));
    }
}
