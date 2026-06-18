package com.redculture.platform.vo.request;

import com.redculture.platform.enums.ReachabilityLevel;
import com.redculture.platform.enums.SchoolResourceRelationType;
import com.redculture.platform.enums.TravelMode;
import lombok.Data;

@Data
public class SchoolResourceRelCreateRequest {

    private Long schoolId;

    private Long resourceId;

    private SchoolResourceRelationType relationType;

    private Integer distanceMeters;

    private TravelMode recommendedTravelMode;

    private Integer estimatedDurationMinutes;

    private ReachabilityLevel reachabilityLevel;

    private Integer priorityLevel;

    private String educationThemeSummary;

    private Long sourceId;
}
