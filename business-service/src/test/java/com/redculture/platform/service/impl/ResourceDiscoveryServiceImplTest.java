package com.redculture.platform.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.entity.ResourceDiscoveryRun;
import com.redculture.platform.entity.School;
import com.redculture.platform.enums.DiscoveryRunStatus;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.mapper.ResourceDiscoveryCandidateMapper;
import com.redculture.platform.mapper.ResourceDiscoveryRunItemMapper;
import com.redculture.platform.mapper.ResourceDiscoveryRunMapper;
import com.redculture.platform.service.LocalEduResourceService;
import com.redculture.platform.service.SchoolResourceRelService;
import com.redculture.platform.service.SchoolService;
import com.redculture.platform.service.discovery.AmapPoiClient;
import com.redculture.platform.service.discovery.ResourceDiscoveryWorker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResourceDiscoveryServiceImplTest {

    @Test
    void reusesFreshCachedRunWithoutStartingWorker() {
        ResourceDiscoveryRunMapper runMapper = mock(ResourceDiscoveryRunMapper.class);
        ResourceDiscoveryRunItemMapper itemMapper = mock(ResourceDiscoveryRunItemMapper.class);
        ResourceDiscoveryWorker worker = mock(ResourceDiscoveryWorker.class);
        SchoolService schoolService = mock(SchoolService.class);
        School school = approvedSchool();
        when(schoolService.getById(2L)).thenReturn(school);

        ResourceDiscoveryRun cached = new ResourceDiscoveryRun();
        cached.setRunId(8L);
        cached.setSchoolId(2L);
        cached.setRadiusMeters(5000);
        cached.setProvider("amap");
        cached.setStatus(DiscoveryRunStatus.COMPLETED);
        cached.setCacheExpiresAt(LocalDateTime.now().plusHours(2));
        when(runMapper.selectOne(any())).thenReturn(cached);
        when(itemMapper.selectList(any())).thenReturn(java.util.List.of());

        ResourceDiscoveryServiceImpl service = service(runMapper, itemMapper, worker, schoolService);

        var result = service.startRun(2L, 5, false);

        assertEquals(8L, result.getRunId());
        assertEquals(true, result.getCacheHit());
        verify(worker, never()).executeAsync(any());
        verify(runMapper, never()).insert(any(ResourceDiscoveryRun.class));
    }

    @Test
    void rejectsUnsupportedRadiusBeforeExternalWork() {
        SchoolService schoolService = mock(SchoolService.class);
        when(schoolService.getById(2L)).thenReturn(approvedSchool());
        ResourceDiscoveryServiceImpl service = service(mock(ResourceDiscoveryRunMapper.class),
                mock(ResourceDiscoveryRunItemMapper.class), mock(ResourceDiscoveryWorker.class), schoolService);

        assertThrows(IllegalArgumentException.class, () -> service.startRun(2L, 7, false));
    }

    private ResourceDiscoveryServiceImpl service(ResourceDiscoveryRunMapper runMapper,
                                                  ResourceDiscoveryRunItemMapper itemMapper,
                                                  ResourceDiscoveryWorker worker,
                                                  SchoolService schoolService) {
        return new ResourceDiscoveryServiceImpl(
                runMapper,
                mock(ResourceDiscoveryCandidateMapper.class),
                itemMapper,
                schoolService,
                mock(LocalEduResourceService.class),
                mock(SchoolResourceRelService.class),
                worker,
                mock(AmapPoiClient.class),
                new AppMapProperties(),
                new ObjectMapper()
        );
    }

    private School approvedSchool() {
        School school = new School();
        school.setSchoolId(2L);
        school.setReviewStatus(ReviewStatus.APPROVED);
        school.setActive(true);
        school.setLongitude(new BigDecimal("113.9815"));
        school.setLatitude(new BigDecimal("38.3452"));
        return school;
    }
}
