package com.redculture.platform.service;

import com.redculture.platform.common.PageResult;
import com.redculture.platform.vo.discovery.DiscoveryCandidateVO;
import com.redculture.platform.vo.request.DiscoveryCandidateReviewRequest;

public interface ResourceDiscoveryReviewService {
    PageResult<DiscoveryCandidateVO> pageCandidates(Long schoolId, String analysisStatus,
                                                     String decisionStatus, Long pageNum, Long pageSize);
    DiscoveryCandidateVO getCandidate(Long candidateId);
    DiscoveryCandidateVO approve(Long candidateId, DiscoveryCandidateReviewRequest request);
    DiscoveryCandidateVO reject(Long candidateId, DiscoveryCandidateReviewRequest request);
    DiscoveryCandidateVO reopen(Long candidateId, DiscoveryCandidateReviewRequest request);
}
