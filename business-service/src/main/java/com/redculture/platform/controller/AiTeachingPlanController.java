package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.service.AiTeachingPlanService;
import com.redculture.platform.service.TeachingActivityPlanService;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.GeneratedTeachingPlanResponse;
import com.redculture.platform.vo.TeachingActivityPlanAdminVO;
import com.redculture.platform.vo.request.GeneratedTeachingPlanSaveRequest;
import com.redculture.platform.vo.request.TeachingPlanGenerateRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/ai/teaching-plans")
//AI 教学活动方案：生成教案、流式生成、保存草稿、查看本人已生成的方案。
public class AiTeachingPlanController {

    /*
    AiTeachingPlanService负责调用 AI Agent 生成方案、流式转发生成过程、保存 AI 生成的草稿。
    TeachingActivityPlanService负责从业务数据库查询已经保存的教学活动方案。
     */
    private final AiTeachingPlanService aiTeachingPlanService;
    private final TeachingActivityPlanService teachingActivityPlanService;

    public AiTeachingPlanController(AiTeachingPlanService aiTeachingPlanService,
                                    TeachingActivityPlanService teachingActivityPlanService) {
        this.aiTeachingPlanService = aiTeachingPlanService;
        this.teachingActivityPlanService = teachingActivityPlanService;
    }

    //同步生成教学方案。等待 AI 完整生成后，一次性返回结果。
    @PostMapping("/generate")
    public ApiResponse<GeneratedTeachingPlanResponse> generate(@RequestBody TeachingPlanGenerateRequest request,
                                                               HttpServletRequest servletRequest) {
        try {
            AuthCurrentUserVO user = AuthContext.requireUser(servletRequest);
            requireSchoolAccess(request == null ? null : request.getSchoolId(), user);
            return ApiResponse.success(aiTeachingPlanService.generatePlan(
                    request, user.getAccountId(), request == null ? null : request.getThreadId()));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    //流式生成教学方案。AI 生成一段就推送一段，适用于前端实时展示。
    @PostMapping(value = "/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateStream(@RequestBody TeachingPlanGenerateRequest request,
                                     HttpServletRequest servletRequest) {
        AuthCurrentUserVO user = AuthContext.requireUser(servletRequest);
        requireSchoolAccess(request == null ? null : request.getSchoolId(), user);
        return aiTeachingPlanService.generatePlanStream(
                request, user.getAccountId(), request == null ? null : request.getThreadId());
    }

    //将 AI 生成的方案保存为草稿，后续可在后台继续编辑或管理。
    @PostMapping("/save-draft")
    public ApiResponse<TeachingActivityPlanAdminVO> saveDraft(@RequestBody GeneratedTeachingPlanSaveRequest request,
                                                              HttpServletRequest servletRequest) {
        try {
            requireSchoolAccess(request == null ? null : request.getSchoolId(), AuthContext.requireUser(servletRequest));
            return ApiResponse.success("draft activity plan created", aiTeachingPlanService.saveDraft(request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    //查询当前登录用户所在学校的教学活动方案，最多返回前 50 条。
    @GetMapping("/mine")
    public ApiResponse<PageResult<TeachingActivityPlanAdminVO>> mine(HttpServletRequest servletRequest) {
        AuthCurrentUserVO user = AuthContext.currentUser(servletRequest);
        if (user == null || user.getSchoolId() == null) {
            return ApiResponse.fail("school account is required");
        }
        return ApiResponse.success(teachingActivityPlanService.listBySchoolId(user.getSchoolId(), 1L, 50L));
    }

    //核心权限校验方法
    /*
    情况	结果
未登录，或请求没有学校 ID	抛出 school account is required。
当前用户是 platform_admin	可操作任意学校的数据。
当前用户不是平台管理员，但请求的 schoolId 等于自己的学校 ID	允许。
当前用户不是平台管理员，且请求的是其他学校 ID	拒绝，提示 cannot access another school。
     */
    private void requireSchoolAccess(Long schoolId, AuthCurrentUserVO user) {
        if (user == null || schoolId == null) {
            throw new IllegalArgumentException("school account is required");
        }
        if (!"platform_admin".equals(user.getRoleCode()) && !schoolId.equals(user.getSchoolId())) {
            throw new IllegalArgumentException("cannot access another school");
        }
    }
}
