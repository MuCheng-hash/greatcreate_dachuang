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
import com.redculture.platform.common.PageResult;
import com.redculture.platform.vo.StudentClassSummaryVO;
import com.redculture.platform.vo.StudentClassDetailVO;
import com.redculture.platform.vo.StudentClassActivityVO;

@RestController
@RequestMapping({"/api/student", "/student"})
public class StudentClassTaskController {
    private final TeacherClassService service;
    public StudentClassTaskController(TeacherClassService service) { this.service = service; }

    @PostMapping("/classes/join-by-invite")
    public ApiResponse<TeacherClassVO> join(@RequestBody InviteJoinRequest body, HttpServletRequest request) { try { return ApiResponse.success(service.joinByInvite(body, AuthContext.requireUser(request))); } catch (IllegalArgumentException exception) { return ApiResponse.fail(exception.getMessage()); } }
    @GetMapping("/class-tasks")
    public ApiResponse<List<ClassTaskVO>> tasks(HttpServletRequest request) { try { return ApiResponse.success(service.studentTasks(AuthContext.requireUser(request))); } catch (IllegalArgumentException exception) { return ApiResponse.fail(exception.getMessage()); } }
    @PostMapping("/class-tasks/{taskId}/complete")
    public ApiResponse<Void> complete(@PathVariable Long taskId, HttpServletRequest request) { try { service.completeTask(taskId, AuthContext.requireUser(request)); return ApiResponse.success(null); } catch (IllegalArgumentException exception) { return ApiResponse.fail(exception.getMessage()); } }
    @GetMapping("/classes") public ApiResponse<List<StudentClassSummaryVO>> classes(HttpServletRequest request) { try { return ApiResponse.success(service.listStudentClasses(AuthContext.requireUser(request))); } catch (IllegalArgumentException e) { return ApiResponse.fail(e.getMessage()); } }
    @GetMapping("/classes/{classId}") public ApiResponse<StudentClassDetailVO> classDetail(@PathVariable Long classId, HttpServletRequest request) { try { return ApiResponse.success(service.studentClassDetail(classId, AuthContext.requireUser(request))); } catch (IllegalArgumentException e) { return ApiResponse.fail(e.getMessage()); } }
    @GetMapping("/classes/{classId}/tasks") public ApiResponse<PageResult<ClassTaskVO>> classTasks(@PathVariable Long classId, @RequestParam(required = false) Long pageNum, @RequestParam(required = false) Long pageSize, HttpServletRequest request) { try { return ApiResponse.success(service.studentClassTasks(classId, pageNum, pageSize, AuthContext.requireUser(request))); } catch (IllegalArgumentException e) { return ApiResponse.fail(e.getMessage()); } }
    @GetMapping("/classes/{classId}/activities") public ApiResponse<PageResult<StudentClassActivityVO>> activities(@PathVariable Long classId, @RequestParam(required = false) Long pageNum, @RequestParam(required = false) Long pageSize, HttpServletRequest request) { try { return ApiResponse.success(service.studentClassActivities(classId, pageNum, pageSize, AuthContext.requireUser(request))); } catch (IllegalArgumentException e) { return ApiResponse.fail(e.getMessage()); } }
}
