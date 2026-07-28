package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.service.agent.AgentRuntimeClient;
import com.redculture.platform.vo.ai.LlmModelOption;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai/models")
public class LlmModelController {
    private final AgentRuntimeClient agentRuntimeClient;

    public LlmModelController(AgentRuntimeClient agentRuntimeClient) {
        this.agentRuntimeClient = agentRuntimeClient;
    }

    @GetMapping
    public ApiResponse<List<LlmModelOption>> list(HttpServletRequest request) {
        if (AuthContext.currentUser(request) == null) {
            return ApiResponse.fail(401, "school account is required");
        }
        try {
            return ApiResponse.success(agentRuntimeClient.listModels());
        } catch (RuntimeException exception) {
            return ApiResponse.success(List.of());
        }
    }
}
