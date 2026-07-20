package com.redculture.platform.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.entity.DataSource;
import com.redculture.platform.entity.LocalEduResource;
import com.redculture.platform.entity.ResourceDiscoveryCandidate;
import com.redculture.platform.entity.School;
import com.redculture.platform.entity.SchoolResourceRel;
import com.redculture.platform.enums.DiscoveryAnalysisStatus;
import com.redculture.platform.enums.DiscoveryDecisionStatus;
import com.redculture.platform.enums.ResourceCategory;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.mapper.DataSourceMapper;
import com.redculture.platform.mapper.ResourceDiscoveryCandidateMapper;
import com.redculture.platform.service.LocalEduResourceService;
import com.redculture.platform.service.SchoolResourceRelService;
import com.redculture.platform.service.SchoolService;
import com.redculture.platform.vo.request.DiscoveryCandidateReviewRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResourceDiscoveryReviewServiceImplTest {

    @Test
    void approvalCreatesApprovedResourceAndRelationOnce() {
        ResourceDiscoveryCandidateMapper candidateMapper = mock(ResourceDiscoveryCandidateMapper.class);
        LocalEduResourceService resourceService = mock(LocalEduResourceService.class);
        SchoolResourceRelService relationService = mock(SchoolResourceRelService.class);
        SchoolService schoolService = mock(SchoolService.class);
        DataSourceMapper sourceMapper = mock(DataSourceMapper.class);
        ResourceDiscoveryCandidate candidate = candidate();
        when(candidateMapper.selectById(4L)).thenReturn(candidate);
        when(schoolService.getById(2L)).thenReturn(school());
        when(resourceService.getOne(any())).thenReturn(null);
        when(relationService.getOne(any())).thenReturn(null);
        DataSource source = new DataSource();
        source.setSourceId(9L);
        when(sourceMapper.selectOne(any())).thenReturn(source);
        doAnswer(invocation -> {
            LocalEduResource resource = invocation.getArgument(0);
            resource.setResourceId(22L);
            return true;
        }).when(resourceService).save(any(LocalEduResource.class));

        ResourceDiscoveryReviewServiceImpl service = new ResourceDiscoveryReviewServiceImpl(
                candidateMapper, resourceService, relationService, schoolService, sourceMapper, new ObjectMapper());
        DiscoveryCandidateReviewRequest request = new DiscoveryCandidateReviewRequest();
        request.setReviewerName("admin");

        service.approve(4L, request);

        ArgumentCaptor<LocalEduResource> resourceCaptor = ArgumentCaptor.forClass(LocalEduResource.class);
        verify(resourceService).save(resourceCaptor.capture());
        assertEquals(ReviewStatus.APPROVED, resourceCaptor.getValue().getReviewStatus());
        assertEquals("amap", resourceCaptor.getValue().getExternalProvider());
        ArgumentCaptor<SchoolResourceRel> relationCaptor = ArgumentCaptor.forClass(SchoolResourceRel.class);
        verify(relationService).save(relationCaptor.capture());
        assertEquals(ReviewStatus.APPROVED, relationCaptor.getValue().getReviewStatus());
        assertEquals(DiscoveryDecisionStatus.APPROVED, candidate.getDecisionStatus());
        assertEquals(22L, candidate.getMatchedResourceId());
    }

    private ResourceDiscoveryCandidate candidate() {
        ResourceDiscoveryCandidate candidate = new ResourceDiscoveryCandidate();
        candidate.setCandidateId(4L);
        candidate.setSchoolId(2L);
        candidate.setProvider("amap");
        candidate.setProviderPlaceId("B012345");
        candidate.setPlaceName("示例纪念馆");
        candidate.setDistanceMeters(1200);
        candidate.setAiCategory(ResourceCategory.PATRIOTISM_BASE);
        candidate.setAiConfidence(new BigDecimal("0.88"));
        candidate.setAiRationale("适合开展爱国主义教育");
        candidate.setAnalysisStatus(DiscoveryAnalysisStatus.COMPLETED);
        candidate.setDecisionStatus(DiscoveryDecisionStatus.PENDING);
        return candidate;
    }

    private School school() {
        School school = new School();
        school.setSchoolId(2L);
        return school;
    }
}
