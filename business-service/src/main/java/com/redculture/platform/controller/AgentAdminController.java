package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.service.agent.AgentAdminClient;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/agent")
public class AgentAdminController {

    private final AgentAdminClient agentAdminClient;

    public AgentAdminController(AgentAdminClient agentAdminClient) {
        this.agentAdminClient = agentAdminClient;
    }

    @GetMapping("/observability/summary")
    public ApiResponse<Map<String, Object>> summary(@RequestParam Map<String, String> filters) {
        return ApiResponse.success(agentAdminClient.observabilitySummary(filters));
    }

    @GetMapping("/observability/traces")
    public ApiResponse<List<Map<String, Object>>> traces(@RequestParam Map<String, String> filters) {
        return ApiResponse.success(agentAdminClient.observabilityTraces(filters));
    }

    @GetMapping("/observability/tool-traces")
    public ApiResponse<List<Map<String, Object>>> toolTraces(@RequestParam Map<String, String> filters) {
        return ApiResponse.success(agentAdminClient.toolTraces(filters));
    }

    @GetMapping("/prompts/{promptKey}/versions")
    public ApiResponse<List<Map<String, Object>>> promptVersions(@PathVariable String promptKey) {
        return ApiResponse.success(agentAdminClient.promptVersions(promptKey));
    }

    @GetMapping("/prompts/{promptKey}/metrics")
    public ApiResponse<List<Map<String, Object>>> promptMetrics(@PathVariable String promptKey) {
        return ApiResponse.success(agentAdminClient.promptMetrics(promptKey));
    }

    @PostMapping("/prompts/{promptKey}/versions/{version}/activate")
    public ApiResponse<Map<String, Object>> activatePrompt(@PathVariable String promptKey,
                                                            @PathVariable String version) {
        return ApiResponse.success(agentAdminClient.activatePrompt(promptKey, version));
    }

    @ExceptionHandler(RestClientException.class)
    public ApiResponse<Void> handleUpstreamFailure(RestClientException exception,
                                                   HttpServletResponse response) {
        int status = upstreamStatus(exception);
        response.setStatus(status);
        String message = status == HttpStatus.SERVICE_UNAVAILABLE.value()
                ? "Agent 管理服务暂时不可用"
                : "Agent 管理服务请求失败";
        return ApiResponse.fail(status, message);
    }

    private int upstreamStatus(RestClientException exception) {
        if (exception instanceof ResourceAccessException) {
            return HttpStatus.SERVICE_UNAVAILABLE.value();
        }
        if (exception instanceof RestClientResponseException responseException) {
            int upstream = responseException.getStatusCode().value();
            if (upstream == 404) {
                return HttpStatus.NOT_FOUND.value();
            }
            if (upstream >= 500) {
                return HttpStatus.SERVICE_UNAVAILABLE.value();
            }
        }
        return HttpStatus.BAD_GATEWAY.value();
    }
}
