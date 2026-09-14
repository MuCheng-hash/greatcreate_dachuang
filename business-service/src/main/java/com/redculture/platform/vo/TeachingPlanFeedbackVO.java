package com.redculture.platform.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 教学方案反馈视图对象。 */
@Data
public class TeachingPlanFeedbackVO {
    /** 反馈标识。 */
    private Long feedbackId;
    /** 生成标识。 */
    private Long generationId;
    /** 当前反馈或建议是否已采纳。 */
    private Boolean adopted;
    /** 评分。 */
    private Integer rating;
    /** 原因编码列表。 */
    private List<String> reasonCodes;
    /** 教师说明。 */
    private String teacherNote;
    /** 已保存方案标识。 */
    private Long savedPlanId;
    /** 提交时间。 */
    private LocalDateTime submittedAt;
}
