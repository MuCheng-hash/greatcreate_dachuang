package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.config.AgentProperties;
import com.redculture.platform.entity.LocalEduResource;
import com.redculture.platform.service.AgentToolService;
import com.redculture.platform.service.admin.RagWebSourceService;
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
//给 FastAPI Agent 调用的内部工具接口，包括学校上下文、资源详情、知识检索、关系查询和健康检查。
public class AgentToolController {

    private static final String SERVICE_TOKEN_HEADER = "X-Agent-Service-Token";

    private final AgentProperties agentProperties;
    private final AgentToolService agentToolService;
    private final RagWebSourceService ragWebSourceService;

    public AgentToolController(AgentProperties agentProperties, AgentToolService agentToolService,
                               RagWebSourceService ragWebSourceService) {
        this.agentProperties = agentProperties;
        this.agentToolService = agentToolService;
        this.ragWebSourceService = ragWebSourceService;
    }

    //内部健康检查。FastAPI Agent 可在启动或就绪检查时确认 Java 业务服务是否可用。成功时返回服务状态 up。
    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health(
            @RequestHeader(value = SERVICE_TOKEN_HEADER, required = false) String token) {
        if (!authorized(token)) {
            return ApiResponse.fail(403, "agent service token is invalid");
        }
        return ApiResponse.success(Map.of("status", "up", "service", "business-service"));
    }

    @GetMapping("/web-source-domains")
    public ApiResponse<Map<String, Object>> webSourceDomains(
            @RequestHeader(value = SERVICE_TOKEN_HEADER, required = false) String token) {
        if (!authorized(token)) {
            return ApiResponse.fail(403, "agent service token is invalid");
        }
        return ApiResponse.success(Map.of("domains", ragWebSourceService.enabledDomains()));
    }

    //获取当前问答所属学校的可信上下文，例如学校名称、位置、所属区域、可用资源等。用于让 AI 知道“这位用户所在的是哪所学校”。
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

    //查询某一条思政教育资源的完整详情，例如名称、位置、介绍、适用信息等。
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

    //执行知识库检索。通常会根据用户问题，到 RAG 索引、数据库或其他知识源中找相关片段，并返回可引用的资料。
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

    //执行关系型知识查询，重点查询实体之间的关联，例如“某红色遗址关联哪些人物、事件或故事”。通常会涉及 Neo4j 图谱或关系数据
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

    //内部令牌校验
    /*
    从配置 AgentProperties 中读取服务端预期令牌 internalServiceToken。
确认配置的令牌和请求头中的令牌都不为空。
用 MessageDigest.isEqual(...) 比较两个 UTF-8 字节数组。
只有内容完全一致时才返回 true。
     */
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
