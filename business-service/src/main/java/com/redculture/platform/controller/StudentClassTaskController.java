package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.service.TeacherClassService;
import com.redculture.platform.vo.ClassTaskVO;
import com.redculture.platform.vo.TeacherClassVO;
import com.redculture.platform.vo.request.InviteJoinRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/student")
public class StudentClassTaskController {
    private final TeacherClassService service;
    public StudentClassTaskController(TeacherClassService service) { this.service = service; }

    @PostMapping("/classes/join-by-invite")
    public ApiResponse<TeacherClassVO> join(@RequestBody InviteJoinRequest body, HttpServletRequest request) { try { return ApiResponse.success(service.joinByInvite(body, AuthContext.requireUser(request))); } catch (IllegalArgumentException exception) { return ApiResponse.fail(exception.getMessage()); } }
    @GetMapping("/class-tasks")
    public ApiResponse<List<ClassTaskVO>> tasks(HttpServletRequest request) { try { return ApiResponse.success(service.studentTasks(AuthContext.requireUser(request))); } catch (IllegalArgumentException exception) { return ApiResponse.fail(exception.getMessage()); } }
    @PostMapping("/class-tasks/{taskId}/complete")
    public ApiResponse<Void> complete(@PathVariable Long taskId, HttpServletRequest request) { try { service.completeTask(taskId, AuthContext.requireUser(request)); return ApiResponse.success(null); } catch (IllegalArgumentException exception) { return ApiResponse.fail(exception.getMessage()); } }
}
