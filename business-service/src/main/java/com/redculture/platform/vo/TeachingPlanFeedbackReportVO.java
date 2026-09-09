package com.redculture.platform.vo;

import com.redculture.platform.common.PageResult;
import lombok.Data;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** 教学方案反馈报告视图对象。 */
@Data
public class TeachingPlanFeedbackReportVO {
    /** 生成数量。 */
    private long generationCount;
    /** 反馈数量。 */
    private long feedbackCount;
    /** 反馈比例。 */
    private BigDecimal feedbackRate;
    /** 已采纳数量。 */
    private long adoptedCount;
    /** 未采纳的反馈数量。 */
    private long notAdoptedCount;
    /** 采纳比例。 */
    private BigDecimal adoptionRate;
    /** 平均评分。 */
    private BigDecimal averageRating;
    /** 按评分值统计的数量分布。 */
    private Map<Integer, Long> ratingDistribution = new LinkedHashMap<>();
    /** 按反馈原因统计的数量分布。 */
    private Map<String, Long> reasonDistribution = new LinkedHashMap<>();
    /** 明细。 */
    private PageResult<TeachingPlanFeedbackReportItemVO> details;
}
