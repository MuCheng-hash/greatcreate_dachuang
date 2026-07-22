package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.DiscoveryAnalysisStatus;
import com.redculture.platform.enums.DiscoveryDecisionStatus;
import com.redculture.platform.enums.ResourceCategory;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "resource_discovery_candidate", autoResultMap = true)
public class ResourceDiscoveryCandidate extends BaseAuditEntity {

    @TableId(value = "candidate_id", type = IdType.AUTO)
    private Long candidateId;

    @TableField("school_id")
    private Long schoolId;

    @TableField("provider")
    private String provider;

    @TableField("provider_place_id")
    private String providerPlaceId;

    @TableField("place_name")
    private String placeName;

    @TableField("address")
    private String address;

    @TableField("longitude")
    private BigDecimal longitude;

    @TableField("latitude")
    private BigDecimal latitude;

    @TableField("provider_type_code")
    private String providerTypeCode;

    @TableField("provider_type_name")
    private String providerTypeName;

    @TableField("contact_phone")
    private String contactPhone;

    @TableField("opening_hours")
    private String openingHours;

    @TableField("distance_meters")
    private Integer distanceMeters;

    @TableField("raw_json")
    private String rawJson;

    @TableField("analysis_status")
    private DiscoveryAnalysisStatus analysisStatus;

    @TableField("ideological_relevant")
    private Boolean ideologicalRelevant;

    @TableField("ai_category")
    private ResourceCategory aiCategory;

    @TableField("ai_subcategory")
    private String aiSubcategory;

    @TableField("ai_confidence")
    private BigDecimal aiConfidence;

    @TableField("ai_rationale")
    private String aiRationale;

    @TableField("education_themes_json")
    private String educationThemesJson;

    @TableField("target_grades")
    private String targetGrades;

    @TableField("activity_suggestion")
    private String activitySuggestion;

    @TableField("verification_notes")
    private String verificationNotes;

    @TableField("decision_status")
    private DiscoveryDecisionStatus decisionStatus;

    @TableField("matched_resource_id")
    private Long matchedResourceId;

    @TableField("last_error")
    private String lastError;

    @TableField("first_seen_at")
    private LocalDateTime firstSeenAt;

    @TableField("last_seen_at")
    private LocalDateTime lastSeenAt;

    @TableField("last_analyzed_at")
    private LocalDateTime lastAnalyzedAt;

    @TableField("reviewed_by")
    private String reviewedBy;

    @TableField("reviewed_at")
    private LocalDateTime reviewedAt;

    @TableField("review_remark")
    private String reviewRemark;
}
