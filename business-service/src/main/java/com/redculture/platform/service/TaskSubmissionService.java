package com.redculture.platform.service;

import com.redculture.platform.vo.*;
import com.redculture.platform.vo.request.StudentTaskReviewRequest;
import com.redculture.platform.vo.request.StudentTaskSubmissionRequest;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface TaskSubmissionService {
    record DownloadAttachment(Resource resource, String filename, String contentType) { }
    StudentTaskDetailVO studentTaskDetail(Long taskId, AuthCurrentUserVO user);
    StudentTaskSubmissionVO createSubmission(Long taskId, StudentTaskSubmissionRequest request, AuthCurrentUserVO user);
    StudentTaskAttachmentVO uploadAttachment(Long submissionId, MultipartFile file, AuthCurrentUserVO user);
    StudentTaskSubmissionVO submit(Long submissionId, AuthCurrentUserVO user);
    List<StudentTaskSubmissionVO> history(Long submissionId, AuthCurrentUserVO user);
    List<StudentTaskSubmissionVO> teacherSubmissions(Long taskId, AuthCurrentUserVO user);
    StudentTaskSubmissionVO teacherSubmissionDetail(Long submissionId, AuthCurrentUserVO user);
    StudentTaskSubmissionVO review(Long submissionId, StudentTaskReviewRequest request, AuthCurrentUserVO user);
    TaskStatisticsVO statistics(Long taskId, AuthCurrentUserVO user);
    List<TaskResourceVO> availableResources(AuthCurrentUserVO user);
    DownloadAttachment download(Long attachmentId, AuthCurrentUserVO user);
}
