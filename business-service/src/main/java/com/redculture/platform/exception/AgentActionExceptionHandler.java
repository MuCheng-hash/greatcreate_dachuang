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

    @ExceptionHandler(AgentBusyException.class)
    public ResponseEntity<ApiResponse<Void>> handleAgentBusy(AgentBusyException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                ApiResponse.fail(HttpStatus.SERVICE_UNAVAILABLE.value(), "agent_busy")
        );
    }

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
