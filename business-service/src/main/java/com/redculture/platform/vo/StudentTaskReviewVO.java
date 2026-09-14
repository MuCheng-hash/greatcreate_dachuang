package com.redculture.platform.vo;
import lombok.Data;
import java.time.LocalDateTime;
/** 学生任务审核视图对象。 */
@Data public class StudentTaskReviewVO { /** 审核标识。 */ private Long reviewId; /** 教师名称。 */ private String teacherName; /** 本次审核动作。 具体取值由任务审核协议定义。 */ private String reviewAction; /** 评语或备注内容。 */ private String comment; /** 年级。 */ private String grade; /** 审核时间。 */ private LocalDateTime reviewedAt; }
