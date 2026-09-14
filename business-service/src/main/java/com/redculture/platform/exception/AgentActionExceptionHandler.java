package com.redculture.platform.exception;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.service.agent.IdempotencyConflictException;
import com.redculture.platform.service.agent.AgentBusyException;
import com.redculture.platform.service.agent.AgentUpstreamException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 将 Agent 写动作异常转换为统一 HTTP 响应。
 */
@RestControllerAdvice
public class AgentActionExceptionHandler {

    /**
     * 处理匹配的异常并转换为统一 HTTP 响应。
     *
     * @param exception 捕获到的异常
     * @return 统一封装的 HTTP 响应
     */
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleIdempotencyConflict(
            IdempotencyConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                new ApiResponse<>(HttpStatus.CONFLICT.value(), exception.getMessage(),
                        Map.of("code", exception.getCode()))
        );
    }

    /**
     * 处理匹配的异常并转换为统一 HTTP 响应。
     *
     * @param exception 捕获到的异常
     * @return 统一封装的 HTTP 响应
     */
    @ExceptionHandler(AgentBusyException.class)
    public ResponseEntity<ApiResponse<Void>> handleAgentBusy(AgentBusyException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                ApiResponse.fail(HttpStatus.SERVICE_UNAVAILABLE.value(), "agent_busy")
        );
    }

    /**
     * 处理匹配的异常并转换为统一 HTTP 响应。
     *
     * @param exception 捕获到的异常
     * @return 统一封装的 HTTP 响应
     */
    @ExceptionHandler(AgentUpstreamException.class)
    public ResponseEntity<ApiResponse<Void>> handleAgentUpstream(
            AgentUpstreamException exception) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode());
        if (status == null) {
            status = HttpStatus.BAD_GATEWAY;
        }
        return ResponseEntity.status(status).body(
                ApiResponse.fail(status.value(), exception.getCode())
        );
    }
}
