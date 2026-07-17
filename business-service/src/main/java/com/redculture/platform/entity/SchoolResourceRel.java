package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.ReachabilityLevel;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.enums.SchoolResourceRelationType;
import com.redculture.platform.enums.TravelMode;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "school_resource_rel", autoResultMap = true)
public class SchoolResourceRel extends BaseAuditEntity {

    @TableId(value = "rel_id", type = IdType.AUTO)
    private Long relId;

    @TableField("school_id")
    private Long schoolId;

    @TableField("resource_id")
    private Long resourceId;

    @TableField("relation_type")
    private SchoolResourceRelationType relationType;

    @TableField("distance_meters")
    private Integer distanceMeters;

    @TableField("recommended_travel_mode")
    private TravelMode recommendedTravelMode;

    @TableField("estimated_duration_minutes")
    private Integer estimatedDurationMinutes;

    @TableField("reachability_level")
    private ReachabilityLevel reachabilityLevel;

    @TableField("priority_level")
    private Integer priorityLevel;

    @TableField("education_theme_summary")
    private String educationThemeSummary;

    @TableField("source_id")
    private Long sourceId;

    @TableField("review_status")
    private ReviewStatus reviewStatus;
}
