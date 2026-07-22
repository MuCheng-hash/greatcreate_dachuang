package com.redculture.platform.vo.discovery;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class DiscoveryCandidateVO {
    private Long candidateId;
    private Long schoolId;
    private String provider;
    private String providerPlaceId;
    private String placeName;
    private String address;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private String providerTypeCode;
    private String providerTypeName;
    private String contactPhone;
    private String openingHours;
    private Integer distanceMeters;
    private String analysisStatus;
    private Boolean ideologicalRelevant;
    private String aiCategory;
    private String aiSubcategory;
    private BigDecimal aiConfidence;
    private String aiRationale;
    private List<String> educationThemes = new ArrayList<>();
    private String targetGrades;
    private String activitySuggestion;
    private String verificationNotes;
    private String decisionStatus;
    private Long matchedResourceId;
    private String lastError;
    private LocalDateTime lastSeenAt;
    private LocalDateTime lastAnalyzedAt;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private String reviewRemark;
    private boolean unverified = true;
}
