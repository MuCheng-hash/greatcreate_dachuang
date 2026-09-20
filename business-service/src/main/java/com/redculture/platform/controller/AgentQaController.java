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

/**
 * Agent 问答 HTTP 入口，负责把 Servlet 认证上下文转换为受控的同步或 SSE 调用。
 *
 * <p>控制器不信任请求体中的用户、学校或角色字段：所有访问边界均取自
 * {@link AuthContext} 写入的当前认证用户。知识范围、会话归属、轮次幂等、动作状态机
 * 与模型降级由 {@link AgentQaService} 继续执行，本类只负责协议层校验和错误形状统一。</p>
 */
@RestController
@RequestMapping("/api/ai/qa")
public class AgentQaController {

    /** 承担认证范围内的问答、流式生成、轮次取消和高风险动作确认业务。 */
    private final AgentQaService agentQaService;

    /**
     * 注入 Agent 业务服务。
     *
     * @param agentQaService 已配置模型、检索和状态存储边界的问答服务
     */
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
        // 认证拦截器已完成令牌解析；这里不自行读取 Cookie，避免不同入口出现身份解释差异。
        AuthCurrentUserVO currentUser = AuthContext.currentUser(servletRequest);
        if (currentUser == null) {
            // 未认证时不进入业务服务，防止其从请求体或默认范围推断调用方身份。
            return response(HttpStatus.UNAUTHORIZED, "需要学校账号");
        }
        if (Boolean.TRUE.equals(request.getDebug()) && !"platform_admin".equals(currentUser.getRoleCode())) {
            // 调试响应可能携带检索轨迹和范围信息，只允许平台管理员请求。
            return response(HttpStatus.FORBIDDEN, "Agent 调试功能需要平台管理员权限");
        }
        // 延迟到订阅阶段执行，确保响应式链被真正消费时才占用模型、检索和数据库资源。
        return Mono.defer(() -> agentQaService.ask(request, currentUser))
                // 业务成功结果沿用统一 API 信封，前端无需为 Agent 问答维护独立结构。
                .map(value -> ResponseEntity.ok(ApiResponse.success(value)))
                // 领域异常映射为稳定 HTTP 语义，不向普通调用方泄露上游实现细节。
                .onErrorResume(error -> responseError(error, "Agent 请求失败"));
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
        // SSE 响应不得被浏览器、中间代理或 Nginx 转码、缓存或聚合，否则 token 无法实时到达。
        servletResponse.setHeader("Cache-Control", "no-cache, no-transform");
        servletResponse.setHeader("X-Accel-Buffering", "no");
        // 流式入口和同步入口使用同一认证来源，避免通过不同协议绕过数据范围限制。
        AuthCurrentUserVO currentUser = AuthContext.currentUser(servletRequest);
        if (currentUser == null) {
            // 流式接口即使在认证失败时也返回 error 后跟 done，保证前端能统一结束加载状态。
            return errorEvents(
                    "auth_required",
                    "需要学校账号",
                    request == null ? null : request.getClientTurnId(),
                    false
            );
        }
        if (Boolean.TRUE.equals(request.getDebug()) && !"platform_admin".equals(currentUser.getRoleCode())) {
            // 不允许非管理员借助 SSE 订阅调试轨迹或工具事件。
            return errorEvents(
                    "agent_debug_forbidden",
                    "Agent 调试功能需要平台管理员权限",
                    request.getClientTurnId(),
                    false
            );
        }
        // 每次订阅重新建立业务流；断线恢复由相同 clientTurnId 对应的持久化轮次处理。
        return Flux.defer(() -> agentQaService.stream(request, currentUser))
                .onErrorResume(error -> errorEvents(
                        // 参数错误不能重试；上游或连接中断可由客户端使用原轮次键恢复。
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
            // 取消操作必须带认证范围，不能仅凭可猜测的 clientTurnId 终止他人的执行。
            return response(HttpStatus.UNAUTHORIZED, "需要学校账号");
        }
        // 服务层会校验轮次所有权并保持重复取消幂等，控制器不在内存中维护轮次状态。
        return Mono.defer(() -> agentQaService.cancelTurn(clientTurnId, currentUser))
                .map(value -> ResponseEntity.ok(ApiResponse.success(value)))
                .onErrorResume(error -> responseError(error, "取消请求失败"));
    }

    /** 查询当前用户拥有或可操作的 Agent 待确认动作，防止通过 actionId 越权读取。 */
    @GetMapping("/actions/{actionId}")
    public Mono<ResponseEntity<ApiResponse<AgentActionVO>>> getAction(
            @PathVariable String actionId,
            HttpServletRequest servletRequest) {
        AuthCurrentUserVO currentUser = AuthContext.currentUser(servletRequest);
        if (currentUser == null) {
            // 动作详情同样按账号和范围隔离，未认证请求不访问状态存储。
            return response(HttpStatus.UNAUTHORIZED, "需要学校账号");
        }
        return Mono.defer(() -> agentQaService.getAction(actionId, currentUser))
                .map(value -> ResponseEntity.ok(ApiResponse.success(value)))
                .onErrorResume(error -> responseError(error, "动作请求被拒绝"));
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
            // 用户确认可能触发写操作，因此先拒绝未认证决策，不能交由模型或请求体补全身份。
            return response(HttpStatus.UNAUTHORIZED, "需要学校账号");
        }
        // 空请求安全地转为 null 决策，再由服务层按状态机拒绝；避免控制器抛出未规范化空指针错误。
        return Mono.defer(() -> agentQaService.decideAction(
                        actionId,
                        request == null ? null : request.getDecision(),
                        currentUser
                ))
                .map(value -> ResponseEntity.ok(ApiResponse.success(value)))
                .onErrorResume(error -> responseError(error, "动作决策被拒绝"));
    }

    private <T> Mono<ResponseEntity<ApiResponse<T>>> response(
            HttpStatus status, String message) {
        // 所有控制器提前拒绝和错误恢复共用同一信封，HTTP 状态与业务 code 保持一致。
        return Mono.just(ResponseEntity.status(status).body(
                ApiResponse.fail(status.value(), message)
        ));
    }

    /** 将领域错误归一为 HTTP 错误，避免把上游实现细节泄露到普通问答响应。 */
    private <T> Mono<ResponseEntity<ApiResponse<T>>> responseError(
            Throwable error, String fallbackMessage) {
        if (error instanceof IllegalArgumentException) {
            // 参数、范围或状态机前置条件错误属于调用方可修正的问题，映射为 400。
            return response(
                    HttpStatus.BAD_REQUEST,
                    error.getMessage() == null ? fallbackMessage : error.getMessage()
            );
        }
        if (error instanceof AgentBusyException) {
            // 阻塞调度器或 Agent 执行容量已满时提示稍后重试，而不是错误地归类为参数问题。
            return response(HttpStatus.SERVICE_UNAVAILABLE, "Agent 当前繁忙，请稍后重试");
        }
        if (error instanceof AgentUpstreamException upstream) {
            // 上游服务已提供可公开的状态码和机器码时保留它们，控制器不传递原始异常消息。
            HttpStatus status = HttpStatus.resolve(upstream.getStatusCode());
            return response(
                    status == null ? HttpStatus.BAD_GATEWAY : status,
                    upstream.getCode()
            );
        }
        // 未分类异常统一按网关故障收口，避免暴露数据库、模型或网络栈信息。
        return response(HttpStatus.BAD_GATEWAY, fallbackMessage);
    }

    /** 为流式接口构造终止事件；无论失败来源如何，前端都能收到明确的流结束信号。 */
    private Flux<ServerSentEvent<Map<String, Object>>> errorEvents(
            String code,
            String message,
            String clientTurnId,
            boolean retryable) {
        // 使用有序 Map 固定事件字段顺序，便于客户端日志和断线诊断稳定展示。
        Map<String, Object> error = new LinkedHashMap<>();
        // code 和 errorType 同时保留以兼容不同前端消费者的错误分类字段。
        error.put("code", code);
        error.put("errorType", code);
        error.put("message", message);
        error.put("clientTurnId", clientTurnId);
        error.put("retryable", retryable);
        return Flux.just(
                // error 事件承载机器码、用户可见信息和恢复所需轮次键。
                ServerSentEvent.<Map<String, Object>>builder()
                        .event("error")
                        .data(error)
                        .build(),
                // done 是流式协议终止标记，即使失败也必须发送以防前端永久等待。
                ServerSentEvent.<Map<String, Object>>builder()
                        .event("done")
                        .data(Map.of())
                        .build()
        );
    }
}
