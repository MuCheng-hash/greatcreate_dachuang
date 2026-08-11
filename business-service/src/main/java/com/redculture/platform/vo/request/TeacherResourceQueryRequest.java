package com.redculture.platform.vo.request;
import lombok.Data;
@Data public class TeacherResourceQueryRequest { private String keyword; private String category; private String gradeName; private String travelMode; private String reachabilityLevel; private Integer maxDistanceMeters; private Boolean favoritesOnly; }
