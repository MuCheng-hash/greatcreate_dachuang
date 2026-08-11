package com.redculture.platform.vo;
import lombok.Data;
import java.math.BigDecimal;
@Data public class TeacherResourceVO { private Long resourceId; private String resourceName; private String resourceCategory; private String targetGrade; private String address; private BigDecimal longitude; private BigDecimal latitude; private String intro; private String educationValue; private String safetyNote; private Integer distanceMeters; private Integer priorityLevel; private String travelMode; private Integer durationMinutes; private String reachabilityLevel; private String educationThemeSummary; private boolean favorited; private boolean available; }
