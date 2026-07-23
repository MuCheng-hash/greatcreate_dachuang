package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.service.AgentQaService;
import com.redculture.platform.service.AuthService;
import com.redculture.platform.vo.AgentQaResponse;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.request.AgentQaRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/qa")
public class AgentQaController {

    private final AgentQaService agentQaService;
    private final AuthService authService;

    public AgentQaController(AgentQaService agentQaService, AuthService authService) {
        this.agentQaService = agentQaService;
        this.authService = authService;
    }

    @PostMapping("/ask")
    public ApiResponse<AgentQaResponse> ask(@RequestBody AgentQaRequest request, HttpSession session) {
        AuthCurrentUserVO currentUser = authService.currentUser(session);
        if (currentUser == null) {
            return ApiResponse.fail(401, "school account is required");
        }
        try {
            return ApiResponse.success(agentQaService.ask(request, currentUser));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody AgentQaRequest request, HttpSession session) {
        AuthCurrentUserVO currentUser = authService.currentUser(session);
        if (currentUser == null) {
            return errorEmitter("AUTH_REQUIRED", "school account is required");
        }
        try {
            return agentQaService.stream(request, currentUser);
        } catch (IllegalArgumentException exception) {
            return errorEmitter("REQUEST_INVALID", exception.getMessage());
        }
    }

    private SseEmitter errorEmitter(String errorType, String message) {
        SseEmitter emitter = new SseEmitter(1000L);
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of(
                    "errorType", errorType,
                    "message", message == null ? "request failed" : message
            )));
            emitter.send(SseEmitter.event().name("done").data(Map.of()));
            emitter.complete();
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        }
        return emitter;
    }
}
