package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.service.agent.AgentRuntimeClient;
import com.redculture.platform.vo.ai.LlmModelOption;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/ai/models")
//返回当前用户可选择的 AI 模型列表。
public class LlmModelController {
    private final AgentRuntimeClient agentRuntimeClient;

    public LlmModelController(AgentRuntimeClient agentRuntimeClient) {
        this.agentRuntimeClient = agentRuntimeClient;
    }

    @GetMapping
    public Mono<ApiResponse<List<LlmModelOption>>> list(HttpServletRequest request) {
        if (AuthContext.currentUser(request) == null) {
            return Mono.just(ApiResponse.fail(401, "school account is required"));
        }
        return agentRuntimeClient.listModels()
                .map(ApiResponse::success)
                .onErrorReturn(ApiResponse.success(List.of()));
    }
}
