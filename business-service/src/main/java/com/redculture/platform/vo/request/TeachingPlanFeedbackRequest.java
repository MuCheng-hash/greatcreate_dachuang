package com.redculture.platform.vo.request;

import lombok.Data;

import java.util.List;

/** 教学方案反馈请求参数。 */
@Data
public class TeachingPlanFeedbackRequest {

    /** 当前反馈或建议是否已采纳。 */
    private Boolean adopted;

    /** 评分。 */
    private Integer rating;

    /** 原因编码列表。 */
    private List<String> reasonCodes;

    /** 教师说明。 */
    private String teacherNote;
}
