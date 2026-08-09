package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.service.agent.AgentRuntimeClient;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.ai.AssistantConversationDetail;
import com.redculture.platform.vo.ai.AssistantConversationTurnRecovery;
import com.redculture.platform.vo.ai.AssistantConversationSummary;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai/qa/history")
//AI 对话历史：列出会话、查看详情、异常恢复、归档和恢复归档会话
public class AssistantConversationHistoryController {
    private final AgentRuntimeClient agentRuntimeClient;

    public AssistantConversationHistoryController(AgentRuntimeClient agentRuntimeClient) {
        this.agentRuntimeClient = agentRuntimeClient;
    }

    //查询会话列表，默认只查询活跃会话。
    @GetMapping
    public ApiResponse<List<AssistantConversationSummary>> list(
            @RequestParam(name = "status", defaultValue = "active") String status,
            HttpServletRequest request) {
        AuthCurrentUserVO user = requireSchoolUser(request);
        return ApiResponse.success(agentRuntimeClient.listConversations(
                agentRuntimeClient.ownerIdFor(user), "SCHOOL", user.getSchoolId(), normalizeStatus(status)));
    }

    //查询某个会话的完整详情，包括历史提问和 AI 回答。
    @GetMapping("/{threadId}")
    public ApiResponse<AssistantConversationDetail> detail(
            @PathVariable String threadId, HttpServletRequest request) {
        AuthCurrentUserVO user = requireSchoolUser(request);
        return ApiResponse.success(agentRuntimeClient.getConversation(
                threadId, agentRuntimeClient.ownerIdFor(user), "SCHOOL", user.getSchoolId()));
    }

    //按客户端回合 ID 找回某次对话的执行结果，用于网络中断、页面刷新或前端没有及时收到最终结果时恢复内容。
    @GetMapping("/recovery/{clientTurnId}")
    public ApiResponse<AssistantConversationTurnRecovery> recover(
            @PathVariable String clientTurnId, HttpServletRequest request) {
        AuthCurrentUserVO user = requireSchoolUser(request);
        return ApiResponse.success(agentRuntimeClient.recoverConversationTurn(
                clientTurnId, agentRuntimeClient.ownerIdFor(user), "SCHOOL", user.getSchoolId()));
    }

    //归档会话，不是物理删除。归档后默认列表不再显示，但消息记录仍保留。
    @DeleteMapping("/{threadId}")
    public ApiResponse<Void> archive(
            @PathVariable String threadId, HttpServletRequest request) {
        AuthCurrentUserVO user = requireSchoolUser(request);
        agentRuntimeClient.archiveConversation(
                threadId, agentRuntimeClient.ownerIdFor(user), "SCHOOL", user.getSchoolId());
        return ApiResponse.success(null);
    }

    //将归档会话恢复为活跃状态，使其重新出现在默认会话列表中。
    @PostMapping("/{threadId}/restore")
    public ApiResponse<Void> restore(
            @PathVariable String threadId, HttpServletRequest request) {
        AuthCurrentUserVO user = requireSchoolUser(request);
        agentRuntimeClient.restoreConversation(
                threadId, agentRuntimeClient.ownerIdFor(user), "SCHOOL", user.getSchoolId());
        return ApiResponse.success(null);
    }

    //规范和校验对话状态
    /*
    未传 status：默认使用 active。
自动去掉前后空格并转成小写。
只允许 active 和 archived，其他值会抛出异常。
     */
    private String normalizeStatus(String status) {
        String normalized = status == null ? "active" : status.trim().toLowerCase();
        if (!"active".equals(normalized) && !"archived".equals(normalized)) {
            throw new IllegalArgumentException("status must be active or archived");
        }
        return normalized;
    }

    //是访问控制方法
    //它从当前请求中读取登录用户,并要求该用户已登录 + 已绑定 schoolId
    private AuthCurrentUserVO requireSchoolUser(HttpServletRequest request) {
        AuthCurrentUserVO user = AuthContext.currentUser(request);
        if (user == null || user.getSchoolId() == null) {
            throw new IllegalArgumentException("school account is required");
        }
        return user;
    }
}
