package com.redculture.platform.service.agent;

import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.AgentCitationVO;
import com.redculture.platform.vo.request.AgentQaRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Calls the stateful FastAPI runtime after Spring has resolved authorization and trusted context. */
@Component
public class AgentRuntimeClient {

    private final AppMapProperties properties;
    public AgentRuntimeClient(AppMapProperties properties) {
        this.properties = properties;
    }

    public AgentRuntimeResult generate(AgentQaRequest request, AuthCurrentUserVO user, AgentAnswerContext context) {
        if (!properties.isAgentRuntimeEnabled() || !StringUtils.hasText(properties.getLlmServiceBaseUrl())) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ownerId", ownerId(user));
        body.put("scopeType", context.getScopeType().name());
        body.put("scopeId", context.getScopeId());
        body.put("threadId", request.getThreadId());
        body.put("message", context.getQuestion());
        body.put("grade", context.getGrade());
        body.put("theme", context.getTheme());
        body.put("intent", context.getIntent() == null ? null : context.getIntent().name());
        body.put("context", trustedContext(context));
        try {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(3_000);
            requestFactory.setReadTimeout(30_000);
            AgentRuntimeResponse response = RestClient.builder()
                    .baseUrl(properties.getLlmServiceBaseUrl())
                    .requestFactory(requestFactory)
                    .build()
                    .post()
                    .uri("/agent/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(AgentRuntimeResponse.class);
            if (response == null || !StringUtils.hasText(response.getAnswer())) {
                return null;
            }
            List<String> citationIds = response.getCitations() == null ? new ArrayList<>() : response.getCitations().stream()
                    .map(AgentCitationVO::getCitationId)
                    .filter(StringUtils::hasText)
                    .toList();
            List<String> followUps = response.getFollowUpQuestions() == null ? new ArrayList<>() : response.getFollowUpQuestions();
            List<String> toolNames = response.getToolExecutions() == null ? new ArrayList<>() : response.getToolExecutions().stream()
                    .map(ToolExecutionResponse::getName)
                    .filter(StringUtils::hasText)
                    .toList();
            return new AgentRuntimeResult(
                    new GeneratedAnswer(response.getAnswer(), citationIds, followUps),
                    response.getThreadId(), response.getStatus(), toolNames
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private String ownerId(AuthCurrentUserVO user) {
        if (user.getAccountId() != null) {
            return "account:" + user.getAccountId();
        }
        return "username:" + (user.getUsername() == null ? "unknown" : user.getUsername());
    }

    private Map<String, Object> trustedContext(AgentAnswerContext context) {
        Map<String, Object> trusted = new LinkedHashMap<>();
        if (context.getSchoolDetail() != null) {
            trusted.put("school", context.getSchoolDetail().getSchool());
            trusted.put("resources", context.getSchoolDetail().getResources());
        }
        trusted.put("region", context.getRegionDetail());
        trusted.put("resource", context.getResource());
        trusted.put("retrieval", context.getRetrieval());
        trusted.put("citationCandidates", context.getRetrieval() == null
                ? List.of() : context.getRetrieval().getCitationCandidates());
        return trusted;
    }

    @lombok.Data
    public static class AgentRuntimeResponse {
        private String threadId;
        private String answer;
        private String status;
        private List<AgentCitationVO> citations = new ArrayList<>();
        private List<String> relatedResources = new ArrayList<>();
        private List<String> followUpQuestions = new ArrayList<>();
        private List<ToolExecutionResponse> toolExecutions = new ArrayList<>();
    }

    @lombok.Data
    public static class ToolExecutionResponse {
        private String name;
        private String status;
        private Integer durationMs;
    }
}
