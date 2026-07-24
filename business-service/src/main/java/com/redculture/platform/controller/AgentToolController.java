package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.config.AgentProperties;
import com.redculture.platform.entity.LocalEduResource;
import com.redculture.platform.service.AgentToolService;
import com.redculture.platform.vo.ai.AgentToolRequest;
import com.redculture.platform.vo.ai.KnowledgeRetrieveResult;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@RestController
@RequestMapping("/internal/agent/tools")
public class AgentToolController {

    private static final String SERVICE_TOKEN_HEADER = "X-Agent-Service-Token";

    private final AgentProperties agentProperties;
    private final AgentToolService agentToolService;

    public AgentToolController(AgentProperties agentProperties, AgentToolService agentToolService) {
        this.agentProperties = agentProperties;
        this.agentToolService = agentToolService;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health(
            @RequestHeader(value = SERVICE_TOKEN_HEADER, required = false) String token) {
        if (!authorized(token)) {
            return ApiResponse.fail(403, "agent service token is invalid");
        }
        return ApiResponse.success(Map.of("status", "up", "service", "business-service"));
    }

    @PostMapping("/school-context")
    public ApiResponse<Map<String, Object>> schoolContext(
            @RequestHeader(value = SERVICE_TOKEN_HEADER, required = false) String token,
            @RequestBody AgentToolRequest request) {
        if (!authorized(token)) {
            return ApiResponse.fail(403, "agent service token is invalid");
        }
        try {
            return ApiResponse.success(agentToolService.schoolContext(request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(403, exception.getMessage());
        }
    }

    @PostMapping("/resource-detail")
    public ApiResponse<LocalEduResource> resourceDetail(
            @RequestHeader(value = SERVICE_TOKEN_HEADER, required = false) String token,
            @RequestBody AgentToolRequest request) {
        if (!authorized(token)) {
            return ApiResponse.fail(403, "agent service token is invalid");
        }
        try {
            return ApiResponse.success(agentToolService.resourceDetail(request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(403, exception.getMessage());
        }
    }

    @PostMapping("/knowledge-retrieve")
    public ApiResponse<KnowledgeRetrieveResult> knowledgeRetrieve(
            @RequestHeader(value = SERVICE_TOKEN_HEADER, required = false) String token,
            @RequestBody AgentToolRequest request) {
        if (!authorized(token)) {
            return ApiResponse.fail(403, "agent service token is invalid");
        }
        try {
            return ApiResponse.success(agentToolService.knowledgeRetrieve(request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(403, exception.getMessage());
        }
    }

    @PostMapping("/relation-query")
    public ApiResponse<KnowledgeRetrieveResult> relationQuery(
            @RequestHeader(value = SERVICE_TOKEN_HEADER, required = false) String token,
            @RequestBody AgentToolRequest request) {
        if (!authorized(token)) {
            return ApiResponse.fail(403, "agent service token is invalid");
        }
        try {
            return ApiResponse.success(agentToolService.relationQuery(request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(403, exception.getMessage());
        }
    }

    private boolean authorized(String actualToken) {
        String expectedToken = agentProperties.getInternalServiceToken();
        if (!StringUtils.hasText(expectedToken) || !StringUtils.hasText(actualToken)) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                actualToken.getBytes(StandardCharsets.UTF_8)
        );
    }
}
