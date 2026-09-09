package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;

/** 教学方案反馈报告摘要视图对象。 */
@Data
public class TeachingPlanFeedbackReportSummaryVO {
    /** 生成数量。 */
    private long generationCount;
    /** 反馈数量。 */
    private long feedbackCount;
    /** 已采纳数量。 */
    private long adoptedCount;
    /** 未采纳的反馈数量。 */
    private long notAdoptedCount;
    /** 平均评分。 */
    private BigDecimal averageRating;
    /** 一星评分数量。 */
    private long ratingOneCount;
    /** 二星评分数量。 */
    private long ratingTwoCount;
    /** 三星评分数量。 */
    private long ratingThreeCount;
    /** 四星评分数量。 */
    private long ratingFourCount;
    /** 五星评分数量。 */
    private long ratingFiveCount;
}
