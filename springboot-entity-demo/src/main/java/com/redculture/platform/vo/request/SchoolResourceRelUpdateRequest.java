package com.redculture.platform.vo.request;

import com.redculture.platform.enums.ReachabilityLevel;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.enums.SchoolResourceRelationType;
import com.redculture.platform.enums.TravelMode;
import lombok.Data;

@Data
public class SchoolResourceRelUpdateRequest {

    private SchoolResourceRelationType relationType;

    private Integer distanceMeters;

    private TravelMode recommendedTravelMode;

    private Integer estimatedDurationMinutes;

    private ReachabilityLevel reachabilityLevel;

    private Integer priorityLevel;

    private String educationThemeSummary;

    private Long sourceId;

    private ReviewStatus reviewStatus;
}
