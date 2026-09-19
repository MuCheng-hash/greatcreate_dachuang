package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.service.AgentQaService;
import com.redculture.platform.service.agent.AgentBusyException;
import com.redculture.platform.service.agent.AgentUpstreamException;
import com.redculture.platform.vo.AgentQaResponse;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.ai.AgentActionVO;
import com.redculture.platform.vo.ai.AssistantConversationTurnCancellation;
import com.redculture.platform.vo.request.AgentActionDecisionRequest;
import com.redculture.platform.vo.request.AgentQaRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/qa")
public class AgentQaController {

    private final AgentQaService agentQaService;

    public AgentQaController(AgentQaService agentQaService) {
        this.agentQaService = agentQaService;
    }

    /**
     * 执行一次完整的 Agent 问答并以普通 HTTP 响应返回。
     * 用户身份只从认证上下文读取；调试模式仅对 platform_admin 开放，服务层继续依据该身份收窄可检索数据。
     */
    @PostMapping("/ask")
    public Mono<ResponseEntity<ApiResponse<AgentQaResponse>>> ask(
            @RequestBody AgentQaRequest request,
            HttpServletRequest servletRequest) {
        AuthCurrentUserVO currentUser = AuthContext.currentUser(servletRequest);
        if (currentUser == null) {
            return response(HttpStatus.UNAUTHORIZED, "school account is required");
        }
        if (Boolean.TRUE.equals(request.getDebug()) && !"platform_admin".equals(currentUser.getRoleCode())) {
            return response(HttpStatus.FORBIDDEN, "agent debug requires platform administrator");
        }
        return Mono.defer(() -> agentQaService.ask(request, currentUser))
                .map(value -> ResponseEntity.ok(ApiResponse.success(value)))
                .onErrorResume(error -> responseError(error, "agent request failed"));
    }

    /**
     * 建立 Agent SSE 流，将增量结果、工具事件和最终事件转发给客户端。
     * 禁用代理缓冲以保留实时性；连接异常时仍发送 error 与 done，客户端可凭 clientTurnId 恢复可重试轮次。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> stream(
            @RequestBody AgentQaRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        servletResponse.setHeader("Cache-Control", "no-cache, no-transform");
        servletResponse.setHeader("X-Accel-Buffering", "no");
        AuthCurrentUserVO currentUser = AuthContext.currentUser(servletRequest);
        if (currentUser == null) {
            return errorEvents(
                    "auth_required",
                    "school account is required",
                    request == null ? null : request.getClientTurnId(),
                    false
            );
        }
        if (Boolean.TRUE.equals(request.getDebug()) && !"platform_admin".equals(currentUser.getRoleCode())) {
            return errorEvents(
                    "agent_debug_forbidden",
                    "agent debug requires platform administrator",
                    request.getClientTurnId(),
                    false
            );
        }
        return Flux.defer(() -> agentQaService.stream(request, currentUser))
                .onErrorResume(error -> errorEvents(
                        error instanceof IllegalArgumentException
                                ? "request_invalid" : "agent_stream_interrupted",
                        error instanceof IllegalArgumentException && error.getMessage() != null
                                ? error.getMessage() : "连接中断，可使用同一 clientTurnId 恢复本轮执行",
                        request == null ? null : request.getClientTurnId(),
                        !(error instanceof IllegalArgumentException)
                ));
    }

    /** 取消当前认证账号创建的会话轮次，避免客户端断连后后台继续执行无效任务。 */
    @PostMapping("/turns/{clientTurnId}/cancel")
    public Mono<ResponseEntity<ApiResponse<AssistantConversationTurnCancellation>>> cancelTurn(
            @PathVariable String clientTurnId,
            HttpServletRequest servletRequest) {
        AuthCurrentUserVO currentUser = AuthContext.currentUser(servletRequest);
        if (currentUser == null) {
            return response(HttpStatus.UNAUTHORIZED, "school account is required");
        }
        return Mono.defer(() -> agentQaService.cancelTurn(clientTurnId, currentUser))
                .map(value -> ResponseEntity.ok(ApiResponse.success(value)))
                .onErrorResume(error -> responseError(error, "cancel request failed"));
    }

    /** 查询当前用户拥有或可操作的 Agent 待确认动作，防止通过 actionId 越权读取。 */
    @GetMapping("/actions/{actionId}")
    public Mono<ResponseEntity<ApiResponse<AgentActionVO>>> getAction(
            @PathVariable String actionId,
            HttpServletRequest servletRequest) {
        AuthCurrentUserVO currentUser = AuthContext.currentUser(servletRequest);
        if (currentUser == null) {
            return response(HttpStatus.UNAUTHORIZED, "school account is required");
        }
        return Mono.defer(() -> agentQaService.getAction(actionId, currentUser))
                .map(value -> ResponseEntity.ok(ApiResponse.success(value)))
                .onErrorResume(error -> responseError(error, "action request was rejected"));
    }

    /**
     * 接收用户对 Agent 动作的最终确认或拒绝。
     * 控制器只提供认证身份和请求决策；动作归属、状态转换及幂等执行均由服务层保证。
     */
    @PostMapping("/actions/{actionId}/decision")
    public Mono<ResponseEntity<ApiResponse<AgentActionVO>>> decideAction(
            @PathVariable String actionId,
            @RequestBody AgentActionDecisionRequest request,
            HttpServletRequest servletRequest) {
        AuthCurrentUserVO currentUser = AuthContext.currentUser(servletRequest);
        if (currentUser == null) {
            return response(HttpStatus.UNAUTHORIZED, "school account is required");
        }
        return Mono.defer(() -> agentQaService.decideAction(
                        actionId,
                        request == null ? null : request.getDecision(),
                        currentUser
                ))
                .map(value -> ResponseEntity.ok(ApiResponse.success(value)))
                .onErrorResume(error -> responseError(error, "action decision was rejected"));
    }

    private <T> Mono<ResponseEntity<ApiResponse<T>>> response(
            HttpStatus status, String message) {
        return Mono.just(ResponseEntity.status(status).body(
                ApiResponse.fail(status.value(), message)
        ));
    }

    /** 将领域错误归一为 HTTP 错误，避免把上游实现细节泄露到普通问答响应。 */
    private <T> Mono<ResponseEntity<ApiResponse<T>>> responseError(
            Throwable error, String fallbackMessage) {
        if (error instanceof IllegalArgumentException) {
            return response(
                    HttpStatus.BAD_REQUEST,
                    error.getMessage() == null ? fallbackMessage : error.getMessage()
            );
        }
        if (error instanceof AgentBusyException) {
            return response(HttpStatus.SERVICE_UNAVAILABLE, "agent_busy");
        }
        if (error instanceof AgentUpstreamException upstream) {
            HttpStatus status = HttpStatus.resolve(upstream.getStatusCode());
            return response(
                    status == null ? HttpStatus.BAD_GATEWAY : status,
                    upstream.getCode()
            );
        }
        return response(HttpStatus.BAD_GATEWAY, fallbackMessage);
    }

    /** 为流式接口构造终止事件；无论失败来源如何，前端都能收到明确的流结束信号。 */
    private Flux<ServerSentEvent<Map<String, Object>>> errorEvents(
            String code,
            String message,
            String clientTurnId,
            boolean retryable) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("errorType", code);
        error.put("message", message);
        error.put("clientTurnId", clientTurnId);
        error.put("retryable", retryable);
        return Flux.just(
                ServerSentEvent.<Map<String, Object>>builder()
                        .event("error")
                        .data(error)
                        .build(),
                ServerSentEvent.<Map<String, Object>>builder()
                        .event("done")
                        .data(Map.of())
                        .build()
        );
    }
}
