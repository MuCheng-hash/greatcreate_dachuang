package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.service.agent.AgentRuntimeClient;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.ai.AssistantConversationDetail;
import com.redculture.platform.vo.ai.AssistantConversationSummary;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai/qa/history")
public class AssistantConversationHistoryController {
    private final AgentRuntimeClient agentRuntimeClient;

    public AssistantConversationHistoryController(AgentRuntimeClient agentRuntimeClient) {
        this.agentRuntimeClient = agentRuntimeClient;
    }

    @GetMapping
    public ApiResponse<List<AssistantConversationSummary>> list(HttpServletRequest request) {
        AuthCurrentUserVO user = requireSchoolUser(request);
        return ApiResponse.success(agentRuntimeClient.listConversations(
                agentRuntimeClient.ownerIdFor(user), "SCHOOL", user.getSchoolId()));
    }

    @GetMapping("/{threadId}")
    public ApiResponse<AssistantConversationDetail> detail(
            @PathVariable String threadId, HttpServletRequest request) {
        AuthCurrentUserVO user = requireSchoolUser(request);
        return ApiResponse.success(agentRuntimeClient.getConversation(
                threadId, agentRuntimeClient.ownerIdFor(user), "SCHOOL", user.getSchoolId()));
    }

    @DeleteMapping("/{threadId}")
    public ApiResponse<Void> archive(
            @PathVariable String threadId, HttpServletRequest request) {
        AuthCurrentUserVO user = requireSchoolUser(request);
        agentRuntimeClient.archiveConversation(
                threadId, agentRuntimeClient.ownerIdFor(user), "SCHOOL", user.getSchoolId());
        return ApiResponse.success(null);
    }

    private AuthCurrentUserVO requireSchoolUser(HttpServletRequest request) {
        AuthCurrentUserVO user = AuthContext.currentUser(request);
        if (user == null || user.getSchoolId() == null) {
            throw new IllegalArgumentException("school account is required");
        }
        return user;
    }
}
