package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.AgeGroup;
import com.redculture.platform.enums.ReviewStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "red_story", autoResultMap = true)
public class RedStory extends BaseAuditEntity {

    @TableId(value = "story_id", type = IdType.AUTO)
    private Long storyId;

    @TableField("story_code")
    private String storyCode;

    @TableField("story_title")
    private String storyTitle;

    @TableField("related_region_id")
    private Long relatedRegionId;

    @TableField("age_group")
    private AgeGroup ageGroup;

    @TableField("summary")
    private String summary;

    @TableField("story_content")
    private String storyContent;

    @TableField("source_id")
    private Long sourceId;

    @TableField("review_status")
    private ReviewStatus reviewStatus;

    @TableField("is_active")
    private Boolean active;
}
