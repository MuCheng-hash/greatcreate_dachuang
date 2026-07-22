package com.redculture.platform.service;

import com.redculture.platform.vo.discovery.ApprovedResourceDetailVO;
import com.redculture.platform.vo.discovery.DiscoveryCandidateVO;
import com.redculture.platform.vo.discovery.DiscoveryRunVO;

public interface ResourceDiscoveryService {
    DiscoveryRunVO startRun(Long schoolId, Integer radiusKm, boolean force);
    DiscoveryRunVO getRun(Long schoolId, Long runId, boolean adminView);
    DiscoveryCandidateVO getCandidate(Long schoolId, Long candidateId, boolean adminView);
    ApprovedResourceDetailVO getApprovedResource(Long schoolId, Long resourceId);
}
