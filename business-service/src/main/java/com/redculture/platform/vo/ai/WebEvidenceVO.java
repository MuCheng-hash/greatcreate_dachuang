package com.redculture.platform.vo.ai;

import lombok.Data;

/** 网络证据视图对象。 */
@Data
public class WebEvidenceVO {

    /** 标题。 */
    private String title;

    /** 访问地址。 */
    private String url;

    /** 领域。 */
    private String domain;

    /** 来源内容的摘要片段。 */
    private String excerpt;

    /** 当前候选结果的排序位次。 */
    private Integer rank;

    /** 外部提供方返回的匹配得分。 */
    private Double providerScore;
}
