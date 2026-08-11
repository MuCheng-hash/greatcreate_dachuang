package com.redculture.platform.exception;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.service.agent.IdempotencyConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class AgentActionExceptionHandler {

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleIdempotencyConflict(
            IdempotencyConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                new ApiResponse<>(HttpStatus.CONFLICT.value(), exception.getMessage(),
                        Map.of("code", exception.getCode()))
        );
    }
}
