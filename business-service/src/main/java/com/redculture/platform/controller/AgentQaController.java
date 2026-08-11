package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.service.AgentQaService;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.vo.AgentQaResponse;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.ai.AssistantConversationTurnCancellation;
import com.redculture.platform.vo.ai.AgentActionVO;
import com.redculture.platform.vo.request.AgentActionDecisionRequest;
import com.redculture.platform.vo.request.AgentQaRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/qa")
//AI 助手问答：普通问答接口及 SSE 流式输出接口。
public class AgentQaController {

    private final AgentQaService agentQaService;

    public AgentQaController(AgentQaService agentQaService) {
        this.agentQaService = agentQaService;
    }

    //对应普通问答接口
    //前端传入 AgentQaRequest，例如用户问题、会话 ID、模型选择等；接口等待 AI 生成完成后，一次性返回完整的 AgentQaResponse。
    @PostMapping("/ask")
    public ApiResponse<AgentQaResponse> ask(@RequestBody AgentQaRequest request, HttpServletRequest servletRequest) {
        AuthCurrentUserVO currentUser = AuthContext.currentUser(servletRequest);
        if (currentUser == null) {
            return ApiResponse.fail(401, "school account is required");
        }
        try {
            return ApiResponse.success(agentQaService.ask(request, currentUser));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    //对应流式问答接口：
    //AI 每生成一小段文字，前端就立刻显示，而无需等整段答案生成完毕
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody AgentQaRequest request,
                             HttpServletRequest servletRequest,
                             HttpServletResponse servletResponse) {
        servletResponse.setHeader("Cache-Control", "no-cache, no-transform");
        servletResponse.setHeader("X-Accel-Buffering", "no");
        AuthCurrentUserVO currentUser = AuthContext.currentUser(servletRequest);
        if (currentUser == null) {
            return errorEmitter("AUTH_REQUIRED", "school account is required");
        }
        try {
            return agentQaService.stream(request, currentUser);
        } catch (IllegalArgumentException exception) {
            return errorEmitter("REQUEST_INVALID", exception.getMessage());
        }
    }

    @PostMapping("/turns/{clientTurnId}/cancel")
    public ApiResponse<AssistantConversationTurnCancellation> cancelTurn(
            @PathVariable String clientTurnId, HttpServletRequest servletRequest) {
        AuthCurrentUserVO currentUser = AuthContext.currentUser(servletRequest);
        if (currentUser == null) {
            return ApiResponse.fail(401, "school account is required");
        }
        try {
            return ApiResponse.success(agentQaService.cancelTurn(clientTurnId, currentUser));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @GetMapping("/actions/{actionId}")
    public ResponseEntity<ApiResponse<AgentActionVO>> getAction(
            @PathVariable String actionId, HttpServletRequest servletRequest) {
        AuthCurrentUserVO currentUser = AuthContext.currentUser(servletRequest);
        if (currentUser == null) {
            return ResponseEntity.status(401).body(
                    ApiResponse.fail(401, "school account is required"));
        }
        try {
            return ResponseEntity.ok(
                    ApiResponse.success(agentQaService.getAction(actionId, currentUser)));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.fail(400, exception.getMessage()));
        } catch (RestClientResponseException exception) {
            return ResponseEntity.status(exception.getStatusCode()).body(
                    ApiResponse.fail(exception.getStatusCode().value(),
                            "action request was rejected"));
        }
    }

    @PostMapping("/actions/{actionId}/decision")
    public ResponseEntity<ApiResponse<AgentActionVO>> decideAction(
            @PathVariable String actionId,
            @RequestBody AgentActionDecisionRequest request,
            HttpServletRequest servletRequest) {
        AuthCurrentUserVO currentUser = AuthContext.currentUser(servletRequest);
        if (currentUser == null) {
            return ResponseEntity.status(401).body(
                    ApiResponse.fail(401, "school account is required"));
        }
        try {
            return ResponseEntity.ok(ApiResponse.success(agentQaService.decideAction(
                    actionId, request == null ? null : request.getDecision(), currentUser)));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.fail(400, exception.getMessage()));
        } catch (RestClientResponseException exception) {
            return ResponseEntity.status(exception.getStatusCode()).body(
                    ApiResponse.fail(exception.getStatusCode().value(),
                            "action decision was rejected"));
        }
    }

    //关闭连接，通知前端本轮请求已结束。
    /*
    两类错误会被转成 SSE 事件：
场景	errorType	含义
用户未登录	AUTH_REQUIRED	没有可用的学校账号身份，不能发起 AI 问答。
请求参数不合法	REQUEST_INVALID	请求内容不符合要求，例如问题为空。
     */
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
