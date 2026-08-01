package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.service.agent.AgentRuntimeClient;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.ai.AgentMemoryItem;
import com.redculture.platform.vo.ai.AgentMemorySetting;
import com.redculture.platform.vo.request.AgentMemoryCreateRequest;
import com.redculture.platform.vo.request.AgentMemorySettingUpdateRequest;
import com.redculture.platform.vo.request.AgentMemoryUpdateRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@RestController
@RequestMapping("/api/ai")
public class AgentMemoryController {

    private static final String SCHOOL_SCOPE = "SCHOOL";
    private static final Set<String> MEMORY_TYPES = Set.of("PROFILE", "TASK");
    private static final Set<String> MEMORY_STATUSES = Set.of("pending", "active", "deleted");

    private final AgentRuntimeClient agentRuntimeClient;

    public AgentMemoryController(AgentRuntimeClient agentRuntimeClient) {
        this.agentRuntimeClient = agentRuntimeClient;
    }

    @GetMapping("/memory-settings")
    public ApiResponse<AgentMemorySetting> settings(HttpServletRequest request) {
        AuthCurrentUserVO user = requireSchoolUser(request);
        return ApiResponse.success(agentRuntimeClient.getMemorySetting(
                ownerId(user), SCHOOL_SCOPE, user.getSchoolId()));
    }

    @PutMapping("/memory-settings")
    public ApiResponse<AgentMemorySetting> updateSettings(
            @RequestBody AgentMemorySettingUpdateRequest request,
            HttpServletRequest servletRequest) {
        if (request == null || request.getEnabled() == null) {
            throw new IllegalArgumentException("enabled is required");
        }
        AuthCurrentUserVO user = requireSchoolUser(servletRequest);
        return ApiResponse.success(agentRuntimeClient.updateMemorySetting(
                ownerId(user), SCHOOL_SCOPE, user.getSchoolId(), request.getEnabled()));
    }

    @GetMapping("/memories")
    public ApiResponse<List<AgentMemoryItem>> list(
            @RequestParam(name = "status", defaultValue = "active") String status,
            @RequestParam(name = "memoryType", required = false) String memoryType,
            HttpServletRequest request) {
        AuthCurrentUserVO user = requireSchoolUser(request);
        return ApiResponse.success(agentRuntimeClient.listMemories(
                ownerId(user),
                SCHOOL_SCOPE,
                user.getSchoolId(),
                normalizeStatus(status),
                normalizeMemoryType(memoryType, false)));
    }

    @PostMapping("/memories")
    public ApiResponse<AgentMemoryItem> create(
            @RequestBody AgentMemoryCreateRequest request,
            HttpServletRequest servletRequest) {
        if (request == null) {
            throw new IllegalArgumentException("memory request is required");
        }
        AuthCurrentUserVO user = requireSchoolUser(servletRequest);
        AgentMemoryCreateRequest normalized = new AgentMemoryCreateRequest(
                normalizeMemoryType(request.getMemoryType(), true),
                normalizeOptionalText(request.getFieldKey()),
                requireContent(request.getContent()));
        return ApiResponse.success(agentRuntimeClient.createMemory(
                ownerId(user), SCHOOL_SCOPE, user.getSchoolId(), normalized));
    }

    @PatchMapping("/memories/{id}")
    public ApiResponse<AgentMemoryItem> update(
            @PathVariable String id,
            @RequestBody AgentMemoryUpdateRequest request,
            HttpServletRequest servletRequest) {
        requireMemoryId(id);
        if (request == null
                || (request.getMemoryType() == null
                && request.getFieldKey() == null
                && request.getContent() == null)) {
            throw new IllegalArgumentException("at least one memory field is required");
        }
        AuthCurrentUserVO user = requireSchoolUser(servletRequest);
        AgentMemoryUpdateRequest normalized = new AgentMemoryUpdateRequest(
                request.getMemoryType() == null
                        ? null : normalizeMemoryType(request.getMemoryType(), true),
                request.getFieldKey() == null
                        ? null : normalizeOptionalText(request.getFieldKey()),
                request.getContent() == null
                        ? null : requireContent(request.getContent()));
        return ApiResponse.success(agentRuntimeClient.updateMemory(
                id.trim(), ownerId(user), SCHOOL_SCOPE, user.getSchoolId(), normalized));
    }

    @PostMapping("/memories/{id}/confirm")
    public ApiResponse<AgentMemoryItem> confirm(
            @PathVariable String id, HttpServletRequest request) {
        AuthCurrentUserVO user = requireSchoolUser(request);
        return ApiResponse.success(agentRuntimeClient.confirmMemory(
                requireMemoryId(id), ownerId(user), SCHOOL_SCOPE, user.getSchoolId()));
    }

    @DeleteMapping("/memories/{id}")
    public ApiResponse<AgentMemoryItem> delete(
            @PathVariable String id, HttpServletRequest request) {
        AuthCurrentUserVO user = requireSchoolUser(request);
        return ApiResponse.success(agentRuntimeClient.deleteMemory(
                requireMemoryId(id), ownerId(user), SCHOOL_SCOPE, user.getSchoolId()));
    }

    @PostMapping("/memories/{id}/restore")
    public ApiResponse<AgentMemoryItem> restore(
            @PathVariable String id, HttpServletRequest request) {
        AuthCurrentUserVO user = requireSchoolUser(request);
        return ApiResponse.success(agentRuntimeClient.restoreMemory(
                requireMemoryId(id), ownerId(user), SCHOOL_SCOPE, user.getSchoolId()));
    }

    @DeleteMapping("/memories/{id}/permanent")
    public ApiResponse<Void> permanentDelete(
            @PathVariable String id, HttpServletRequest request) {
        AuthCurrentUserVO user = requireSchoolUser(request);
        agentRuntimeClient.permanentlyDeleteMemory(
                requireMemoryId(id), ownerId(user), SCHOOL_SCOPE, user.getSchoolId());
        return ApiResponse.success(null);
    }

    private AuthCurrentUserVO requireSchoolUser(HttpServletRequest request) {
        AuthCurrentUserVO user = AuthContext.currentUser(request);
        if (user == null
                || user.getSchoolId() == null
                || !"school_admin".equals(user.getRoleCode())) {
            throw new IllegalArgumentException("school account is required");
        }
        return user;
    }

    private String ownerId(AuthCurrentUserVO user) {
        return agentRuntimeClient.ownerIdFor(user);
    }

    private String normalizeMemoryType(String memoryType, boolean required) {
        if (!StringUtils.hasText(memoryType)) {
            if (required) {
                throw new IllegalArgumentException("memoryType is required");
            }
            return null;
        }
        String normalized = memoryType.trim().toUpperCase(Locale.ROOT);
        if (!MEMORY_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("memoryType must be PROFILE or TASK");
        }
        return normalized;
    }

    private String normalizeStatus(String status) {
        String normalized = StringUtils.hasText(status)
                ? status.trim().toLowerCase(Locale.ROOT) : "active";
        if (!MEMORY_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("status must be pending, active or deleted");
        }
        return normalized;
    }

    private String requireContent(String content) {
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("content is required");
        }
        return content.trim();
    }

    private String normalizeOptionalText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String requireMemoryId(String id) {
        if (!StringUtils.hasText(id)) {
            throw new IllegalArgumentException("memory id is required");
        }
        return id.trim();
    }
}
