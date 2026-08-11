package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.service.TaskSubmissionService;
import com.redculture.platform.vo.StudentTaskAttachmentVO;
import com.redculture.platform.vo.StudentTaskDetailVO;
import com.redculture.platform.vo.StudentTaskSubmissionVO;
import com.redculture.platform.vo.request.StudentTaskSubmissionRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/student")
public class StudentTaskSubmissionController {
    private final TaskSubmissionService service;
    public StudentTaskSubmissionController(TaskSubmissionService service) { this.service = service; }
    @GetMapping("/tasks/{taskId}") public ApiResponse<StudentTaskDetailVO> detail(@PathVariable Long taskId, HttpServletRequest request) { return run(() -> service.studentTaskDetail(taskId, AuthContext.requireUser(request))); }
    @PostMapping("/tasks/{taskId}/submissions") public ApiResponse<StudentTaskSubmissionVO> create(@PathVariable Long taskId, @RequestBody StudentTaskSubmissionRequest body, HttpServletRequest request) { return run(() -> service.createSubmission(taskId, body, AuthContext.requireUser(request))); }
    @PostMapping(value = "/submissions/{submissionId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) public ApiResponse<StudentTaskAttachmentVO> upload(@PathVariable Long submissionId, @RequestParam("file") MultipartFile file, HttpServletRequest request) { return run(() -> service.uploadAttachment(submissionId, file, AuthContext.requireUser(request))); }
    @PostMapping("/submissions/{submissionId}/submit") public ApiResponse<StudentTaskSubmissionVO> submit(@PathVariable Long submissionId, HttpServletRequest request) { return run(() -> service.submit(submissionId, AuthContext.requireUser(request))); }
    @GetMapping("/submissions/{submissionId}/history") public ApiResponse<List<StudentTaskSubmissionVO>> history(@PathVariable Long submissionId, HttpServletRequest request) { return run(() -> service.history(submissionId, AuthContext.requireUser(request))); }
    @GetMapping("/attachments/{attachmentId}/download") public ResponseEntity<?> download(@PathVariable Long attachmentId, HttpServletRequest request) { try { TaskSubmissionService.DownloadAttachment attachment = service.download(attachmentId, AuthContext.requireUser(request)); return ResponseEntity.ok().contentType(MediaType.parseMediaType(attachment.contentType())).header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(attachment.filename(), StandardCharsets.UTF_8).build().toString()).body(attachment.resource()); } catch (IllegalArgumentException exception) { return ResponseEntity.status(403).body(ApiResponse.fail(exception.getMessage())); } }
    private <T> ApiResponse<T> run(Action<T> action) { try { return ApiResponse.success(action.get()); } catch (IllegalArgumentException exception) { return ApiResponse.fail(exception.getMessage()); } }
    @FunctionalInterface private interface Action<T> { T get(); }
}
