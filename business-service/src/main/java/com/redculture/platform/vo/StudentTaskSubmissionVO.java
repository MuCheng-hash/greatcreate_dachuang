package com.redculture.platform.vo;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
@Data public class StudentTaskSubmissionVO { private Long submissionId; private Long taskId; private Long studentId; private String studentName; private Integer versionNo; private String content; private List<Long> selectedResourceIds = new ArrayList<>(); private LocalDateTime submittedAt; private boolean late; private String status; private boolean current; private List<StudentTaskAttachmentVO> attachments = new ArrayList<>(); private List<StudentTaskReviewVO> reviews = new ArrayList<>(); }
