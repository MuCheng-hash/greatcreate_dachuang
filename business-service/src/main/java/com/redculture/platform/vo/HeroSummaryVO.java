package com.redculture.platform.vo;

import lombok.Data;

import java.util.List;

/** 英雄人物摘要视图对象。 */
@Data
public class HeroSummaryVO {

    /** 英雄人物标识。 */
    private Long heroId;

    /** 英雄人物名称。 */
    private String heroName;

    /** 籍贯地点文本。 */
    private String nativePlaceText;

    /** 资料摘要。 */
    private String profileSummary;

    /** 主要事迹。 */
    private String mainDeeds;

    /** 相关资源名称列表。 */
    private List<String> relatedResourceNames;
}
