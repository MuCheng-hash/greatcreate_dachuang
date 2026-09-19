package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.service.TaskSubmissionService;
import com.redculture.platform.vo.StudentTaskAttachmentVO;
import com.redculture.platform.vo.StudentTaskDetailVO;
import com.redculture.platform.vo.StudentTaskSubmissionVO;
import com.redculture.platform.vo.StudentTaskPageVO;
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
/** 学生任务提交入口；每个操作均将认证用户传给服务层，禁止以路径参数替代学生身份。 */
public class StudentTaskSubmissionController {
    private final TaskSubmissionService service;
    public StudentTaskSubmissionController(TaskSubmissionService service) { this.service = service; }
    /** 分页读取当前学生可见任务，不泄露其他班级的任务。 */
    @GetMapping("/tasks") public ApiResponse<StudentTaskPageVO> tasks(@RequestParam(required = false) String status, @RequestParam(required = false) Long pageNum, @RequestParam(required = false) Long pageSize, HttpServletRequest request) { return run(() -> service.studentTaskPage(status, pageNum, pageSize, AuthContext.requireUser(request))); }
    /** 查询单个任务前由服务确认该任务确实分配给当前学生，taskId 本身不构成访问凭证。 */
    @GetMapping("/tasks/{taskId}") public ApiResponse<StudentTaskDetailVO> detail(@PathVariable Long taskId, HttpServletRequest request) { return run(() -> service.studentTaskDetail(taskId, AuthContext.requireUser(request))); }
    /** 为当前学生创建任务草稿，服务校验任务分配和可提交状态。 */
    @PostMapping("/tasks/{taskId}/submissions") public ApiResponse<StudentTaskSubmissionVO> create(@PathVariable Long taskId, @RequestBody StudentTaskSubmissionRequest body, HttpServletRequest request) { return run(() -> service.createSubmission(taskId, body, AuthContext.requireUser(request))); }
    @PostMapping(value = "/submissions/{submissionId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) public ApiResponse<StudentTaskAttachmentVO> upload(@PathVariable Long submissionId, @RequestParam("file") MultipartFile file, HttpServletRequest request) { return run(() -> service.uploadAttachment(submissionId, file, AuthContext.requireUser(request))); }
    /** 提交本人草稿并触发提交状态转换；重复请求由服务按当前状态处理。 */
    @PostMapping("/submissions/{submissionId}/submit") public ApiResponse<StudentTaskSubmissionVO> submit(@PathVariable Long submissionId, HttpServletRequest request) { return run(() -> service.submit(submissionId, AuthContext.requireUser(request))); }
    @GetMapping("/submissions/{submissionId}/history") public ApiResponse<List<StudentTaskSubmissionVO>> history(@PathVariable Long submissionId, HttpServletRequest request) { return run(() -> service.history(submissionId, AuthContext.requireUser(request))); }
    /** 下载附件仍交由服务按提交归属校验；业务拒绝统一转换为 403，避免向学生暴露附件是否存在。 */
    @GetMapping("/attachments/{attachmentId}/download") public ResponseEntity<?> download(@PathVariable Long attachmentId, HttpServletRequest request) { try { TaskSubmissionService.DownloadAttachment attachment = service.download(attachmentId, AuthContext.requireUser(request)); return ResponseEntity.ok().contentType(MediaType.parseMediaType(attachment.contentType())).header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(attachment.filename(), StandardCharsets.UTF_8).build().toString()).body(attachment.resource()); } catch (IllegalArgumentException exception) { return ResponseEntity.status(403).body(ApiResponse.fail(exception.getMessage())); } }
    /** 将可预期的领域校验失败包装为统一响应，其他异常继续交给全局异常处理器记录。 */
    private <T> ApiResponse<T> run(Action<T> action) { try { return ApiResponse.success(action.get()); } catch (IllegalArgumentException exception) { return ApiResponse.fail(exception.getMessage()); } }
    @FunctionalInterface private interface Action<T> { T get(); }
}
