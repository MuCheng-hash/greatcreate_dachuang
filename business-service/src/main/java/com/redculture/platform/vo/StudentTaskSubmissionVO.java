package com.redculture.platform.vo;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
/** 学生任务提交视图对象。 */
@Data public class StudentTaskSubmissionVO { /** 提交标识。 */ private Long submissionId; /** 任务标识。 */ private Long taskId; /** 学生标识。 */ private Long studentId; /** 学生名称。 */ private String studentName; /** 提交内容的版本号。 */ private Integer versionNo; /** 内容。 */ private String content; /** 已选择资源标识列表。 */ private List<Long> selectedResourceIds = new ArrayList<>(); /** 提交时间。 */ private LocalDateTime submittedAt; /** 当前提交是否超过截止时间。 */ private boolean late; /** 当前对象的业务状态。 具体取值由所属业务流程定义；为空表示尚未提供状态。 */ private String status; /** 是否为当前有效版本。 */ private boolean current; /** 附件列表。 */ private List<StudentTaskAttachmentVO> attachments = new ArrayList<>(); /** 审核记录列表。 */ private List<StudentTaskReviewVO> reviews = new ArrayList<>(); }
