package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.service.TeacherRegistrationInviteService;
import com.redculture.platform.vo.TeacherRegistrationInviteVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/schools/{schoolId}/teacher-registration-invites")
public class TeacherRegistrationInviteAdminController {
    private final TeacherRegistrationInviteService inviteService;

    public TeacherRegistrationInviteAdminController(TeacherRegistrationInviteService inviteService) { this.inviteService = inviteService; }

    @GetMapping
    public ApiResponse<List<TeacherRegistrationInviteVO>> list(@PathVariable Long schoolId, HttpServletRequest request) {
        return ApiResponse.success(inviteService.listForSchool(schoolId, AuthContext.requireUser(request)));
    }

    @PostMapping
    public ApiResponse<TeacherRegistrationInviteVO> create(@PathVariable Long schoolId, HttpServletRequest request) {
        return ApiResponse.success("invite created", inviteService.createForSchool(schoolId, AuthContext.requireUser(request)));
    }

    @DeleteMapping("/{inviteId}")
    public ApiResponse<Void> revoke(@PathVariable Long schoolId, @PathVariable Long inviteId, HttpServletRequest request) {
        inviteService.revoke(inviteId, AuthContext.requireUser(request));
        return ApiResponse.success("invite revoked", null);
    }
}
