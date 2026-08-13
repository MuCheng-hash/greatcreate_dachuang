package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.service.agent.AgentAdminClient;
import com.redculture.platform.service.agent.AgentUpstreamException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/agent")
//AI Agent 运维后台：查看调用摘要、调用链路、工具执行记录、记忆指标和提示词版本/效果，并可切换提示词版本。
public class AgentAdminController {

    private final AgentAdminClient agentAdminClient;

    public AgentAdminController(AgentAdminClient agentAdminClient) {
        this.agentAdminClient = agentAdminClient;
    }

    //获取 Agent 运行概览，例如调用总量、成功/失败数、耗时、模型调用等汇总指标。filters 会接收 URL 中所有查询参数，用于按时间、任务类型、模型等筛选。
    @GetMapping("/observability/summary")
    public Mono<ApiResponse<Map<String, Object>>> summary(
            @RequestParam Map<String, String> filters) {
        return agentAdminClient.observabilitySummary(filters).map(ApiResponse::success);
    }

    //获取一次次完整的 Agent 执行记录。例如用户提问后，模型是否调用、调用了哪些工具、最终是否成功、耗时多久。
    @GetMapping("/observability/traces")
    public Mono<ApiResponse<List<Map<String, Object>>>> traces(
            @RequestParam Map<String, String> filters) {
        return agentAdminClient.observabilityTraces(filters).map(ApiResponse::success);
    }

    //获取 Agent 的“工具调用”记录，重点观察它调用了哪些内部能力，例如知识检索、查询学校上下文、查询资源详情，以及调用结果和耗时。
    @GetMapping("/observability/tool-traces")
    public Mono<ApiResponse<List<Map<String, Object>>>> toolTraces(
            @RequestParam Map<String, String> filters) {
        return agentAdminClient.toolTraces(filters).map(ApiResponse::success);
    }

    //获取用户记忆功能的统计数据，例如记忆数量、冲突数量、待确认项等，用于后台监控记忆系统。
    @GetMapping("/memory-metrics")
    public Mono<ApiResponse<Map<String, Object>>> memoryMetrics() {
        return agentAdminClient.memoryMetrics().map(ApiResponse::success);
    }

    //查询某类提示词的所有版本。promptKey 可能是 teaching-plan 或 resource-discovery，用于查看历史版本、当前激活版本等。
    @GetMapping("/prompts/{promptKey}/versions")
    public Mono<ApiResponse<List<Map<String, Object>>>> promptVersions(
            @PathVariable String promptKey) {
        return agentAdminClient.promptVersions(promptKey).map(ApiResponse::success);
    }

    //查询指定提示词各版本的运行效果指标，例如使用次数、成功率、失败率、耗时等，帮助比较版本效果。
    @GetMapping("/prompts/{promptKey}/metrics")
    public Mono<ApiResponse<List<Map<String, Object>>>> promptMetrics(
            @PathVariable String promptKey) {
        return agentAdminClient.promptMetrics(promptKey).map(ApiResponse::success);
    }

    //激活某个提示词版本，使后续对应类型的 AI 请求使用该版本。例如激活 teaching-plan 的 v2。
    @PostMapping("/prompts/{promptKey}/versions/{version}/activate")
    public Mono<ApiResponse<Map<String, Object>>> activatePrompt(
            @PathVariable String promptKey,
            @PathVariable String version) {
        return agentAdminClient.activatePrompt(promptKey, version).map(ApiResponse::success);
    }

    @ExceptionHandler(AgentUpstreamException.class)
    public ApiResponse<Void> handleUpstreamFailure(AgentUpstreamException exception,
                                                   HttpServletResponse response) {
        int status = upstreamStatus(exception);
        response.setStatus(status);
        String message = status == HttpStatus.SERVICE_UNAVAILABLE.value()
                ? "Agent 管理服务暂时不可用"
                : "Agent 管理服务请求失败";
        return ApiResponse.fail(status, message);
    }

    private int upstreamStatus(AgentUpstreamException exception) {
        int upstream = exception.getStatusCode();
        if (upstream == 404) {
            return HttpStatus.NOT_FOUND.value();
        }
        if (upstream == 503 || upstream == 504 || upstream >= 500) {
            return HttpStatus.SERVICE_UNAVAILABLE.value();
        }
        return HttpStatus.BAD_GATEWAY.value();
    }
}
