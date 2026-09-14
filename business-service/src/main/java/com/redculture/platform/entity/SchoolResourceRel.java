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

/**
 * 学校与教育资源关系实体，用于持久化对应业务数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "school_resource_rel", autoResultMap = true)
public class SchoolResourceRel extends BaseAuditEntity {

    /**
     * 学校与教育资源关系标识。
     */
    @TableId(value = "rel_id", type = IdType.AUTO)
    private Long relId;

    /**
     * 关联的学校标识。
     */
    @TableField("school_id")
    private Long schoolId;

    /**
     * 关联的教育资源标识。
     */
    @TableField("resource_id")
    private Long resourceId;

    /**
     * 关系类型。
     */
    @TableField("relation_type")
    private SchoolResourceRelationType relationType;

    /**
     * 距离，单位米。
     */
    @TableField("distance_meters")
    private Integer distanceMeters;

    /**
     * 推荐出行方式，取值由 {@link com.redculture.platform.enums.TravelMode} 定义。
     */
    @TableField("recommended_travel_mode")
    private TravelMode recommendedTravelMode;

    /**
     * 预计时长，单位分钟。
     */
    @TableField("estimated_duration_minutes")
    private Integer estimatedDurationMinutes;

    /**
     * 可达性等级，取值由 {@link com.redculture.platform.enums.ReachabilityLevel} 定义。
     */
    @TableField("reachability_level")
    private ReachabilityLevel reachabilityLevel;

    /**
     * 优先级。
     */
    @TableField("priority_level")
    private Integer priorityLevel;

    /**
     * 资源可支持的教育主题摘要。
     */
    @TableField("education_theme_summary")
    private String educationThemeSummary;

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
}
