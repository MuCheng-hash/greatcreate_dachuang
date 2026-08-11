package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.service.TeacherClassService;
import com.redculture.platform.vo.*;
import com.redculture.platform.vo.request.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/teacher/classes")
public class TeacherClassController {
    private final TeacherClassService service;

    public TeacherClassController(TeacherClassService service) { this.service = service; }

    @GetMapping("/mine")
    public ApiResponse<List<TeacherClassVO>> mine(HttpServletRequest request) { return run(() -> service.listMine(AuthContext.requireUser(request))); }
    @GetMapping("/available-teachers")
    public ApiResponse<List<ClassTeacherVO>> availableTeachers(HttpServletRequest request) { return run(() -> service.availableTeachers(AuthContext.requireUser(request))); }
    @PostMapping
    public ApiResponse<TeacherClassVO> create(@RequestBody TeacherClassSaveRequest body, HttpServletRequest request) { return run(() -> service.create(body, AuthContext.requireUser(request))); }
    @PutMapping("/{classId}")
    public ApiResponse<TeacherClassVO> update(@PathVariable Long classId, @RequestBody TeacherClassSaveRequest body, HttpServletRequest request) { return run(() -> service.update(classId, body, AuthContext.requireUser(request))); }
    @GetMapping("/{classId}")
    public ApiResponse<TeacherClassDetailVO> detail(@PathVariable Long classId, HttpServletRequest request) { return run(() -> service.detail(classId, AuthContext.requireUser(request))); }
    @GetMapping("/{classId}/students")
    public ApiResponse<List<ClassStudentVO>> students(@PathVariable Long classId, HttpServletRequest request) { return run(() -> service.students(classId, AuthContext.requireUser(request))); }
    @GetMapping("/{classId}/available-students")
    public ApiResponse<List<ClassStudentVO>> availableStudents(@PathVariable Long classId, HttpServletRequest request) { return run(() -> service.availableStudents(classId, AuthContext.requireUser(request))); }
    @PostMapping("/{classId}/students")
    public ApiResponse<Void> addStudent(@PathVariable Long classId, @RequestBody ClassStudentAddRequest body, HttpServletRequest request) { return run(() -> { service.addStudent(classId, body == null ? null : body.getStudentId(), AuthContext.requireUser(request)); return null; }); }
    @DeleteMapping("/{classId}/students/{studentId}")
    public ApiResponse<Void> removeStudent(@PathVariable Long classId, @PathVariable Long studentId, HttpServletRequest request) { return run(() -> { service.removeStudent(classId, studentId, AuthContext.requireUser(request)); return null; }); }
    @PostMapping("/{classId}/students/import")
    public ApiResponse<TeacherClassImportResultVO> importStudents(@PathVariable Long classId, @RequestBody ClassStudentImportRequest body, HttpServletRequest request) { return run(() -> service.importStudents(classId, body, AuthContext.requireUser(request))); }
    @PostMapping("/{classId}/invite-code")
    public ApiResponse<String> rotateInvite(@PathVariable Long classId, HttpServletRequest request) { return run(() -> service.rotateInviteCode(classId, AuthContext.requireUser(request))); }
    @DeleteMapping("/{classId}/invite-code")
    public ApiResponse<Void> disableInvite(@PathVariable Long classId, HttpServletRequest request) { return run(() -> { service.disableInviteCode(classId, AuthContext.requireUser(request)); return null; }); }
    @GetMapping("/{classId}/tasks")
    public ApiResponse<List<ClassTaskVO>> tasks(@PathVariable Long classId, HttpServletRequest request) { return run(() -> service.tasks(classId, AuthContext.requireUser(request))); }
    @PostMapping("/{classId}/tasks")
    public ApiResponse<ClassTaskVO> publishTask(@PathVariable Long classId, @RequestBody ClassTaskSaveRequest body, HttpServletRequest request) { return run(() -> service.publishTask(classId, body, AuthContext.requireUser(request))); }

    private <T> ApiResponse<T> run(ThrowingSupplier<T> action) { try { return ApiResponse.success(action.get()); } catch (IllegalArgumentException exception) { return ApiResponse.fail(exception.getMessage()); } }
    @FunctionalInterface private interface ThrowingSupplier<T> { T get(); }
}
