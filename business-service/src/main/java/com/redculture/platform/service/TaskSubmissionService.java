package com.redculture.platform.service;

import com.redculture.platform.vo.*;
import com.redculture.platform.vo.request.StudentTaskReviewRequest;
import com.redculture.platform.vo.request.StudentTaskSubmissionRequest;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface TaskSubmissionService {
    record DownloadAttachment(Resource resource, String filename, String contentType) { }
    /** 在学生身份和班级范围内分页查询本人可处理的任务。 */
    StudentTaskPageVO studentTaskPage(String status, Long pageNum, Long pageSize, AuthCurrentUserVO user);
    /** 读取学生有权查看的任务详情及当前提交进度。 */
    StudentTaskDetailVO studentTaskDetail(Long taskId, AuthCurrentUserVO user);
    /** 为当前学生创建草稿提交；服务校验任务归属和允许提交的状态。 */
    StudentTaskSubmissionVO createSubmission(Long taskId, StudentTaskSubmissionRequest request, AuthCurrentUserVO user);
    /** 将附件写入当前学生的草稿提交，拒绝向他人提交或已封存提交追加文件。 */
    StudentTaskAttachmentVO uploadAttachment(Long submissionId, MultipartFile file, AuthCurrentUserVO user);
    /** 确认提交草稿并推进提交状态，重复请求按现有状态安全处理。 */
    StudentTaskSubmissionVO submit(Long submissionId, AuthCurrentUserVO user);
    /** 返回本人提交的历史版本或处理轨迹，不能借 submissionId 读取其他学生记录。 */
    List<StudentTaskSubmissionVO> history(Long submissionId, AuthCurrentUserVO user);
    /** 教师按任务读取提交列表，服务必须以教师授课班级和学校约束查询。 */
    List<StudentTaskSubmissionVO> teacherSubmissions(Long taskId, AuthCurrentUserVO user);
    /** 读取教师有权查看的单份提交，包括评阅所需的附件和状态信息。 */
    StudentTaskSubmissionVO teacherSubmissionDetail(Long submissionId, AuthCurrentUserVO user);
    /** 教师在其教学范围内评阅提交并记录评语、得分与状态迁移。 */
    StudentTaskSubmissionVO review(Long submissionId, StudentTaskReviewRequest request, AuthCurrentUserVO user);
    /** 汇总教师管理任务的完成和评阅统计，不向无权教师暴露班级学习数据。 */
    TaskStatisticsVO statistics(Long taskId, AuthCurrentUserVO user);
    /** 列出认证教师所在学校可用于任务的资源，返回结果承担前端选择范围的第一层约束。 */
    List<TaskResourceVO> availableResources(AuthCurrentUserVO user);
    /** 校验附件对应提交的访问权后返回文件流及安全下载元数据。 */
    DownloadAttachment download(Long attachmentId, AuthCurrentUserVO user);
}
