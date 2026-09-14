package com.redculture.platform.vo;

import lombok.Data;

/** 已生成教学方案引用视图对象。 */
@Data
public class GeneratedTeachingPlanCitationVO {

    /** 引用证据的稳定标识。 */
    private String citationId;

    /** 标题。 */
    private String title;

    /**
     * 来源类型。
     * 用于区分数据来自内容分块、图谱事实或其他来源。
     */
    private String sourceType;

    /**
     * 相关实体类型。
     * 具体取值由对应业务流程或协议定义。
     */
    private String relatedEntityType;

    /** 相关实体标识。 */
    private Long relatedEntityId;

    /** 来源内容的摘要片段。 */
    private String excerpt;

    /** 访问地址。 */
    private String url;
}
