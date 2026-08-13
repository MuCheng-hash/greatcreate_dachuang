package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.service.agent.AgentMemoryConflictException;
import com.redculture.platform.service.agent.AgentRuntimeClient;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.ai.AgentMemoryConflictPreview;
import com.redculture.platform.vo.ai.AgentMemoryItem;
import com.redculture.platform.vo.ai.AgentMemorySetting;
import com.redculture.platform.vo.request.AgentMemoryCreateRequest;
import com.redculture.platform.vo.request.AgentMemoryResolutionRequest;
import com.redculture.platform.vo.request.AgentMemorySettingUpdateRequest;
import com.redculture.platform.vo.request.AgentMemoryUpdateRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@RestController
@RequestMapping("/api/ai")
//当前用户的 AI 长期记忆管理：开启/关闭记忆、查询、新增、修改、确认冲突、软删除、恢复和永久删除。
public class AgentMemoryController {

    private static final String SCHOOL_SCOPE = "SCHOOL";
    private static final Set<String> MEMORY_TYPES = Set.of("PROFILE", "TASK");
    private static final Set<String> MEMORY_STATUSES = Set.of("pending", "active", "deleted");

    private final AgentRuntimeClient agentRuntimeClient;

    public AgentMemoryController(AgentRuntimeClient agentRuntimeClient) {
        this.agentRuntimeClient = agentRuntimeClient;
    }

    //获取当前账号的“AI 是否使用长期记忆”开关状态。
    @GetMapping("/memory-settings")
    public Mono<ApiResponse<AgentMemorySetting>> settings(HttpServletRequest request) {
        AuthCurrentUserVO user = requireSchoolUser(request);
        return agentRuntimeClient.getMemorySetting(
                        ownerId(user), SCHOOL_SCOPE, user.getSchoolId())
                .map(ApiResponse::success);
    }

    //开启或关闭长期记忆。请求体需要包含 enabled，例如 {"enabled": true}。
    @PutMapping("/memory-settings")
    public Mono<ApiResponse<AgentMemorySetting>> updateSettings(
            @RequestBody AgentMemorySettingUpdateRequest request,
            HttpServletRequest servletRequest) {
        if (request == null || request.getEnabled() == null) {
            throw new IllegalArgumentException("enabled is required");
        }
        AuthCurrentUserVO user = requireSchoolUser(servletRequest);
        return agentRuntimeClient.updateMemorySetting(
                        ownerId(user), SCHOOL_SCOPE, user.getSchoolId(), request.getEnabled())
                .map(ApiResponse::success);
    }

    //查询记忆列表。默认查询已生效的记忆，即 status=active。
    @GetMapping("/memories")
    public Mono<ApiResponse<List<AgentMemoryItem>>> list(
            @RequestParam(name = "status", defaultValue = "active") String status,
            @RequestParam(name = "memoryType", required = false) String memoryType,
            HttpServletRequest request) {
        AuthCurrentUserVO user = requireSchoolUser(request);
        return agentRuntimeClient.listMemories(
                ownerId(user),
                SCHOOL_SCOPE,
                user.getSchoolId(),
                normalizeStatus(status),
                normalizeMemoryType(memoryType, false)).map(ApiResponse::success);
    }

    //手动新增一条记忆。
    @PostMapping("/memories")
    public Mono<ApiResponse<AgentMemoryItem>> create(
            @RequestBody AgentMemoryCreateRequest request,
            HttpServletRequest servletRequest) {
        if (request == null) {
            throw new IllegalArgumentException("memory request is required");
        }
        AuthCurrentUserVO user = requireSchoolUser(servletRequest);
        AgentMemoryCreateRequest normalized = new AgentMemoryCreateRequest(
                normalizeMemoryType(request.getMemoryType(), true),
                normalizeFieldKey(request.getFieldKey()),
                requireContent(request.getContent()),
                request.getReplaceConflicts());
        return agentRuntimeClient.createMemory(
                        ownerId(user), SCHOOL_SCOPE, user.getSchoolId(), normalized)
                .map(ApiResponse::success);
    }

    //局部修改某条记忆，例如只改内容或分类。
    @PatchMapping("/memories/{id}")
    public Mono<ApiResponse<AgentMemoryItem>> update(
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
                        ? null : normalizeFieldKey(request.getFieldKey()),
                request.getContent() == null
                        ? null : requireContent(request.getContent()),
                request.getReplaceConflicts());
        return agentRuntimeClient.updateMemory(
                        id.trim(), ownerId(user), SCHOOL_SCOPE, user.getSchoolId(), normalized)
                .map(ApiResponse::success);
    }

    //查看某条待确认记忆与已有记忆的冲突预览。
    @GetMapping("/memories/{id}/confirmation-preview")
    public Mono<ApiResponse<AgentMemoryConflictPreview>> confirmationPreview(
            @PathVariable String id, HttpServletRequest request) {
        AuthCurrentUserVO user = requireSchoolUser(request);
        return agentRuntimeClient.getMemoryConfirmationPreview(
                        requireMemoryId(id), ownerId(user), SCHOOL_SCOPE, user.getSchoolId())
                .map(ApiResponse::success);
    }

    //确认一条待处理的记忆，使它生效；也可选择覆盖冲突记忆。
    @PostMapping("/memories/{id}/confirm")
    public Mono<ApiResponse<AgentMemoryItem>> confirm(
            @PathVariable String id,
            @RequestBody(required = false) AgentMemoryResolutionRequest resolution,
            HttpServletRequest request) {
        AuthCurrentUserVO user = requireSchoolUser(request);
        return agentRuntimeClient.confirmMemory(
                requireMemoryId(id),
                ownerId(user),
                SCHOOL_SCOPE,
                user.getSchoolId(),
                resolution != null && Boolean.TRUE.equals(resolution.getReplaceConflicts()))
                .map(ApiResponse::success);
    }

    //软删除记忆。数据仍保留，可恢复。
    @DeleteMapping("/memories/{id}")
    public Mono<ApiResponse<AgentMemoryItem>> delete(
            @PathVariable String id, HttpServletRequest request) {
        AuthCurrentUserVO user = requireSchoolUser(request);
        return agentRuntimeClient.deleteMemory(
                        requireMemoryId(id), ownerId(user), SCHOOL_SCOPE, user.getSchoolId())
                .map(ApiResponse::success);
    }

    //恢复已软删除的记忆；如恢复后发生冲突，也可选择替换冲突项。
    @PostMapping("/memories/{id}/restore")
    public Mono<ApiResponse<AgentMemoryItem>> restore(
            @PathVariable String id,
            @RequestBody(required = false) AgentMemoryResolutionRequest resolution,
            HttpServletRequest request) {
        AuthCurrentUserVO user = requireSchoolUser(request);
        return agentRuntimeClient.restoreMemory(
                requireMemoryId(id),
                ownerId(user),
                SCHOOL_SCOPE,
                user.getSchoolId(),
                resolution != null && Boolean.TRUE.equals(resolution.getReplaceConflicts()))
                .map(ApiResponse::success);
    }

    //永久删除记忆，无法恢复。
    @DeleteMapping("/memories/{id}/permanent")
    public Mono<ApiResponse<Void>> permanentDelete(
            @PathVariable String id, HttpServletRequest request) {
        AuthCurrentUserVO user = requireSchoolUser(request);
        return agentRuntimeClient.permanentlyDeleteMemory(
                        requireMemoryId(id), ownerId(user), SCHOOL_SCOPE, user.getSchoolId())
                .thenReturn(ApiResponse.<Void>success(null));
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

    //根据当前登录用户生成 Agent 服务识别的“记忆所属者 ID”。
    private String ownerId(AuthCurrentUserVO user) {
        return agentRuntimeClient.ownerIdFor(user);
    }

    //校验并标准化记忆类型。
    /*
    required=true：必须传入 memoryType，否则报错。
    required=false：不传时允许，返回 null。
    会去除前后空格，并转成大写。
    只接受 PROFILE 和 TASK。
     */
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


    //校验并标准化记忆状态。
     /*
     三种状态分别表示：
    pending：待用户确认的记忆
    active：已生效、AI 可以使用的记忆
    deleted：已软删除、可恢复的记忆
      */
    private String normalizeStatus(String status) {
        String normalized = StringUtils.hasText(status)
                ? status.trim().toLowerCase(Locale.ROOT) : "active";
        if (!MEMORY_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("status must be pending, active or deleted");
        }
        return normalized;
    }

    //确保记忆内容不能为空、不能只有空格。
    /*
    "  希望回答简洁  " -> "希望回答简洁"
       null 或 "   "       -> 报错
     */
    private String requireContent(String content) {
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("content is required");
        }
        return content.trim();
    }

    /*
    用于处理可选文本字段：
    有内容：去除首尾空格后返回。
    未传、空字符串或全为空格：返回 null。
    它常用于 fieldKey 一类允许为空的字段。
     */
    private String normalizeOptionalText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    //规范化记忆字段名，并兼容历史字段名称。
    //也就是说，旧客户端即使还传 response_format，后端也会把它映射为当前统一使用的 answer_format，保证旧数据和旧前端仍可工作。
    private String normalizeFieldKey(String value) {
        String normalized = normalizeOptionalText(value);
        return "response_format".equals(normalized) ? "answer_format" : normalized;
    }

    //确保 URL 中的记忆 ID 不为空，并去掉首尾空格。
    /*
    " mem_001 " -> "mem_001"
null 或 "   " -> 报错
     */
    private String requireMemoryId(String id) {
        if (!StringUtils.hasText(id)) {
            throw new IllegalArgumentException("memory id is required");
        }
        return id.trim();
    }

    //这是异常处理器。
    /*
    当新增、修改、确认或恢复记忆时，若发现记忆冲突，例如：
    旧记忆：回答应简洁
新记忆：回答应详细展开
Agent 服务会抛出 AgentMemoryConflictException。该方法会把异常转换为标准 HTTP 响应：
HTTP 409 Conflict
并将冲突说明和 exception.getPreview() 中的冲突预览数据返回给前端。前端据此弹出确认窗口，让用户选择是否覆盖冲突的旧记忆。
     */
    @ExceptionHandler(AgentMemoryConflictException.class)
    public ResponseEntity<ApiResponse<AgentMemoryConflictPreview>> handleMemoryConflict(
            AgentMemoryConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                new ApiResponse<>(HttpStatus.CONFLICT.value(), exception.getMessage(), exception.getPreview()));
    }
}
