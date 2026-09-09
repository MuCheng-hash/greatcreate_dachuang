package com.redculture.platform.vo;

import lombok.Data;

/** Agent引用视图对象。 */
@Data
public class AgentCitationVO {

    /** 引用证据的稳定标识。 */
    private String citationId;

    /** 标题。 */
    private String title;

    /** 来源内容的摘要片段。 */
    private String excerpt;

    /**
     * 来源类型。
     * 用于区分数据来自内容分块、图谱事实或其他来源。
     */
    private String sourceType;

    /** 当前候选结果的相关性得分。 */
    private Double score;
}
