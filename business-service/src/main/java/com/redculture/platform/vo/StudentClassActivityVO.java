package com.redculture.platform.vo;

import lombok.Data;
import java.time.LocalDateTime;

/** 学生班级活动视图对象。 */
@Data
public class StudentClassActivityVO {
    /** 教学活动类型。 */
    private String activityType;
    /** 标题。 */
    private String title;
    /** 内容。 */
    private String content;
    /** 相关任务标识。 */
    private Long relatedTaskId;
    /** 创建时间。 */
    private LocalDateTime createdAt;
}
