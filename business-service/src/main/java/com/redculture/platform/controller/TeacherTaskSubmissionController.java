package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.service.TaskSubmissionService;
import com.redculture.platform.vo.*;
import com.redculture.platform.vo.request.StudentTaskReviewRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/teacher")
public class TeacherTaskSubmissionController {
    private final TaskSubmissionService service;
    public TeacherTaskSubmissionController(TaskSubmissionService service) { this.service = service; }
    @GetMapping("/tasks/{taskId}/submissions") public ApiResponse<List<StudentTaskSubmissionVO>> submissions(@PathVariable Long taskId, HttpServletRequest request) { return run(() -> service.teacherSubmissions(taskId, AuthContext.requireUser(request))); }
    @GetMapping("/submissions/{submissionId}") public ApiResponse<StudentTaskSubmissionVO> submission(@PathVariable Long submissionId, HttpServletRequest request) { return run(() -> service.teacherSubmissionDetail(submissionId, AuthContext.requireUser(request))); }
    @PostMapping("/submissions/{submissionId}/review") public ApiResponse<StudentTaskSubmissionVO> review(@PathVariable Long submissionId, @RequestBody StudentTaskReviewRequest body, HttpServletRequest request) { return run(() -> service.review(submissionId, body, AuthContext.requireUser(request))); }
    @GetMapping("/tasks/{taskId}/statistics") public ApiResponse<TaskStatisticsVO> statistics(@PathVariable Long taskId, HttpServletRequest request) { return run(() -> service.statistics(taskId, AuthContext.requireUser(request))); }
    @GetMapping("/task-resources") public ApiResponse<List<TaskResourceVO>> resources(HttpServletRequest request) { return run(() -> service.availableResources(AuthContext.requireUser(request))); }
    @GetMapping("/attachments/{attachmentId}/download") public ResponseEntity<?> download(@PathVariable Long attachmentId, HttpServletRequest request) { try { TaskSubmissionService.DownloadAttachment attachment = service.download(attachmentId, AuthContext.requireUser(request)); return ResponseEntity.ok().contentType(MediaType.parseMediaType(attachment.contentType())).header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(attachment.filename(), StandardCharsets.UTF_8).build().toString()).body(attachment.resource()); } catch (IllegalArgumentException exception) { return ResponseEntity.status(403).body(ApiResponse.fail(exception.getMessage())); } }
    private <T> ApiResponse<T> run(Action<T> action) { try { return ApiResponse.success(action.get()); } catch (IllegalArgumentException exception) { return ApiResponse.fail(exception.getMessage()); } }
    @FunctionalInterface private interface Action<T> { T get(); }
}
