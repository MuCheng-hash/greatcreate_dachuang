package com.redculture.platform.vo.discovery;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class DiscoveryRunVO {
    private Long runId;
    private Long schoolId;
    private Integer radiusMeters;
    private String provider;
    private String status;
    private Boolean cacheHit;
    private Boolean forced;
    private Integer providerCount;
    private Integer candidateCount;
    private Integer analysisCount;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime cacheExpiresAt;
    private List<DiscoveryCandidateVO> candidates = new ArrayList<>();
}
