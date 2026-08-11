package com.redculture.platform.vo;
import lombok.Data;
import java.time.LocalDateTime;
@Data public class StudentTaskReviewVO { private Long reviewId; private String teacherName; private String reviewAction; private String comment; private String grade; private LocalDateTime reviewedAt; }
