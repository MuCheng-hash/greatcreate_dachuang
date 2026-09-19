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
@RequestMapping({"/api/teacher", "/teacher"})
/** 教师任务评阅入口；服务层根据认证教师的班级与学校范围二次校验所有路径参数。 */
public class TeacherTaskSubmissionController {
    private final TaskSubmissionService service;
    public TeacherTaskSubmissionController(TaskSubmissionService service) { this.service = service; }
    /** 按教师授课班级读取任务提交，服务层使用教师所属学校和班级收窄查询。 */
    @GetMapping("/tasks/{taskId}/submissions") public ApiResponse<List<StudentTaskSubmissionVO>> submissions(@PathVariable Long taskId, HttpServletRequest request) { return run(() -> service.teacherSubmissions(taskId, AuthContext.requireUser(request))); }
    /** 查看单份提交的评阅详情；路径中的 submissionId 不允许绕过教师任务范围。 */
    @GetMapping("/submissions/{submissionId}") public ApiResponse<StudentTaskSubmissionVO> submission(@PathVariable Long submissionId, HttpServletRequest request) { return run(() -> service.teacherSubmissionDetail(submissionId, AuthContext.requireUser(request))); }
    /** 对教师有权查看的提交记录评阅，并由服务维护评分和评阅状态转换。 */
    @PostMapping("/submissions/{submissionId}/review") public ApiResponse<StudentTaskSubmissionVO> review(@PathVariable Long submissionId, @RequestBody StudentTaskReviewRequest body, HttpServletRequest request) { return run(() -> service.review(submissionId, body, AuthContext.requireUser(request))); }
    /** 汇总任务统计时只统计教师有权管理的班级，防止通过 taskId 推断其他班级学习情况。 */
    @GetMapping("/tasks/{taskId}/statistics") public ApiResponse<TaskStatisticsVO> statistics(@PathVariable Long taskId, HttpServletRequest request) { return run(() -> service.statistics(taskId, AuthContext.requireUser(request))); }
    /** 返回当前教师可用于布置任务的资源集合，不将跨学校资源暴露给前端选择器。 */
    @GetMapping("/task-resources") public ApiResponse<List<TaskResourceVO>> resources(HttpServletRequest request) { return run(() -> service.availableResources(AuthContext.requireUser(request))); }
    /** 附件下载沿用提交访问规则；鉴权失败以 403 返回而非附件存在性信息。 */
    @GetMapping("/attachments/{attachmentId}/download") public ResponseEntity<?> download(@PathVariable Long attachmentId, HttpServletRequest request) { try { TaskSubmissionService.DownloadAttachment attachment = service.download(attachmentId, AuthContext.requireUser(request)); return ResponseEntity.ok().contentType(MediaType.parseMediaType(attachment.contentType())).header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(attachment.filename(), StandardCharsets.UTF_8).build().toString()).body(attachment.resource()); } catch (IllegalArgumentException exception) { return ResponseEntity.status(403).body(ApiResponse.fail(exception.getMessage())); } }
    /** 仅将可预期的业务校验转为失败响应，保证系统异常仍可被统一监控。 */
    private <T> ApiResponse<T> run(Action<T> action) { try { return ApiResponse.success(action.get()); } catch (IllegalArgumentException exception) { return ApiResponse.fail(exception.getMessage()); } }
    @FunctionalInterface private interface Action<T> { T get(); }
}
