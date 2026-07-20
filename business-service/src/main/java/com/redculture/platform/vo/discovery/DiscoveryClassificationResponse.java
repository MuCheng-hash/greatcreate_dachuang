package com.redculture.platform.vo.discovery;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class DiscoveryClassificationResponse {
    private String analysisStatus;
    private String message;
    private List<Result> results = new ArrayList<>();

    @Data
    public static class Result {
        private String providerPlaceId;
        private Boolean ideologicalRelevant;
        private String resourceCategory;
        private String resourceSubcategory;
        private BigDecimal confidence;
        private String rationale;
        private List<String> educationThemes = new ArrayList<>();
        private String targetGrades;
        private String activitySuggestion;
        private String verificationNotes;
    }
}
