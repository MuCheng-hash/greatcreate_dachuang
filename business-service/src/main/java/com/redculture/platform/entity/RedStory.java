package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.AgeGroup;
import com.redculture.platform.enums.ReviewStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 红色故事实体，用于持久化对应业务数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "red_story", autoResultMap = true)
public class RedStory extends BaseAuditEntity {

    /**
     * 红色故事标识。
     */
    @TableId(value = "story_id", type = IdType.AUTO)
    private Long storyId;

    /**
     * 红色故事业务编码。
     */
    @TableField("story_code")
    private String storyCode;

    /**
     * 故事标题。
     */
    @TableField("story_title")
    private String storyTitle;

    /**
     * 故事主要关联的行政区划标识。
     */
    @TableField("related_region_id")
    private Long relatedRegionId;

    /**
     * 适用年龄阶段。
     */
    @TableField("age_group")
    private AgeGroup ageGroup;

    /**
     * 摘要。
     */
    @TableField("summary")
    private String summary;

    /**
     * 故事正文。
     */
    @TableField("story_content")
    private String storyContent;

    /**
     * 关联的数据来源标识。
     */
    @TableField("source_id")
    private Long sourceId;

    /**
     * 内容审核状态，取值由 {@link com.redculture.platform.enums.ReviewStatus} 定义。
     */
    @TableField("review_status")
    private ReviewStatus reviewStatus;

    /**
     * 是否启用。
     */
    @TableField("is_active")
    private Boolean active;
}
