package com.redculture.platform.vo;

import lombok.Data;

/** 教学方案反馈原因数量视图对象。 */
@Data
public class TeachingPlanFeedbackReasonCountVO {
    /** 原因编码。 */
    private String reasonCode;
    /** 原因数量。 */
    private long reasonCount;
}
