package com.redculture.platform.vo;

import lombok.Data;

import java.util.List;

/** 故事摘要视图对象。 */
@Data
public class StorySummaryVO {

    /** 故事标识。 */
    private Long storyId;

    /** 故事标题。 */
    private String storyTitle;

    /** 年龄段分组。 */
    private String ageGroup;

    /** 摘要信息。 */
    private String summary;

    /** 相关实体名称列表。 */
    private List<String> relatedEntityNames;
}
