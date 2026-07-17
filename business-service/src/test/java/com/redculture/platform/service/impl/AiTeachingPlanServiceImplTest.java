package com.redculture.platform.service.impl;

import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.entity.ContentChunk;
import com.redculture.platform.enums.ActivityType;
import com.redculture.platform.enums.EntityType;
import com.redculture.platform.mapper.ContentChunkMapper;
import com.redculture.platform.mapper.DataSourceMapper;
import com.redculture.platform.mapper.EntitySourceRelMapper;
import com.redculture.platform.service.SchoolMapService;
import com.redculture.platform.service.TeachingActivityPlanService;
import com.redculture.platform.vo.GeneratedTeachingPlanResponse;
import com.redculture.platform.vo.LocalEduResourceSummaryVO;
import com.redculture.platform.vo.SchoolMapDetailVO;
import com.redculture.platform.vo.SchoolResourceItemVO;
import com.redculture.platform.vo.SchoolSummaryVO;
import com.redculture.platform.vo.request.TeachingPlanGenerateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTeachingPlanServiceImplTest {

    @Test
    void generatePlanRejectsUnavailableSchoolBeforeLlmCall() {
        SchoolMapService schoolMapService = mock(SchoolMapService.class);
        AiTeachingPlanServiceImpl service = newService(schoolMapService, mock(TeachingActivityPlanService.class),
                Collections.emptyList(), "");
        TeachingPlanGenerateRequest request = request();

        when(schoolMapService.getSchoolDetail(1L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.generatePlan(request));
    }

    @Test
    void generatePlanReturnsLocalDegradedStructureWhenLlmUnavailable() {
        SchoolMapService schoolMapService = mock(SchoolMapService.class);
        TeachingActivityPlanService teachingActivityPlanService = mock(TeachingActivityPlanService.class);
        ContentChunk chunk = new ContentChunk();
        chunk.setChunkId(9L);
        chunk.setEntityType(EntityType.RESOURCE);
        chunk.setEntityId(2L);
        chunk.setChunkTitle("敬老服务资源说明");
        chunk.setChunkText("可组织学生开展尊老爱老、志愿服务和社会责任教育。");

        AiTeachingPlanServiceImpl service = newService(schoolMapService, teachingActivityPlanService,
                List.of(chunk), "");
        when(schoolMapService.getSchoolDetail(1L)).thenReturn(schoolDetail());

        GeneratedTeachingPlanResponse response = service.generatePlan(request());

        assertEquals("degraded", response.getGenerationStatus());
        assertEquals("敬老志愿服务", response.getTheme());
        assertFalse(response.getObjectives().isEmpty());
        assertFalse(response.getCitations().isEmpty());
        verify(teachingActivityPlanService, never()).createPlan(any());
    }

    @Test
    void loadGraphFactsReturnsEmptyWhenNeo4jUnavailable() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class);
        when(neo4jClient.query(anyString())).thenThrow(new RuntimeException("neo4j unavailable"));
        AiTeachingPlanServiceImpl service = newService(mock(SchoolMapService.class), mock(TeachingActivityPlanService.class),
                Collections.emptyList(), "", neo4jClient);

        List<String> facts = ReflectionTestUtils.invokeMethod(service, "loadGraphFacts", 1L);

        assertEquals(Collections.emptyList(), facts);
    }

    @Test
    void loadGraphFactsMapsRowsWhenNeo4jAvailable() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class, RETURNS_DEEP_STUBS);
        Map<String, Object> row = new HashMap<>();
        row.put("resourceName", "Local Museum");
        row.put("theme", "local history");
        row.put("distanceMeters", 800);
        row.put("tags", List.of("history", "practice"));
        when(neo4jClient.query(anyString()).bind(any()).to(anyString()).fetch().all()).thenReturn(List.of(row));
        AiTeachingPlanServiceImpl service = newService(mock(SchoolMapService.class), mock(TeachingActivityPlanService.class),
                Collections.emptyList(), "", neo4jClient);

        List<String> facts = ReflectionTestUtils.invokeMethod(service, "loadGraphFacts", 1L);

        assertFalse(facts.isEmpty());
        assertFalse(facts.get(0).isBlank());
    }

    private AiTeachingPlanServiceImpl newService(SchoolMapService schoolMapService,
                                                 TeachingActivityPlanService teachingActivityPlanService,
                                                 List<ContentChunk> chunks,
                                                 String llmBaseUrl) {
        return newService(schoolMapService, teachingActivityPlanService, chunks, llmBaseUrl, mock(Neo4jClient.class));
    }

    private AiTeachingPlanServiceImpl newService(SchoolMapService schoolMapService,
                                                 TeachingActivityPlanService teachingActivityPlanService,
                                                 List<ContentChunk> chunks,
                                                 String llmBaseUrl,
                                                 Neo4jClient neo4jClient) {
        ContentChunkMapper contentChunkMapper = mock(ContentChunkMapper.class);
        EntitySourceRelMapper entitySourceRelMapper = mock(EntitySourceRelMapper.class);
        DataSourceMapper dataSourceMapper = mock(DataSourceMapper.class);
        AppMapProperties properties = new AppMapProperties();
        properties.setLlmServiceBaseUrl(llmBaseUrl);

        when(contentChunkMapper.selectList(any())).thenReturn(chunks);
        when(entitySourceRelMapper.selectList(any())).thenReturn(Collections.emptyList());

        return new AiTeachingPlanServiceImpl(
                schoolMapService,
                teachingActivityPlanService,
                contentChunkMapper,
                entitySourceRelMapper,
                dataSourceMapper,
                neo4jClient,
                properties
        );
    }

    private TeachingPlanGenerateRequest request() {
        TeachingPlanGenerateRequest request = new TeachingPlanGenerateRequest();
        request.setSchoolId(1L);
        request.setGrade("四年级");
        request.setTheme("敬老志愿服务");
        request.setActivityType(ActivityType.VOLUNTEER_SERVICE);
        request.setDurationMinutes(120);
        request.setPracticeRequired(true);
        return request;
    }

    private SchoolMapDetailVO schoolDetail() {
        SchoolSummaryVO school = new SchoolSummaryVO();
        school.setSchoolId(1L);
        school.setSchoolName("里庄小学");

        LocalEduResourceSummaryVO resource = new LocalEduResourceSummaryVO();
        resource.setResourceId(2L);
        resource.setResourceName("常安镇敬老院");
        resource.setEducationValue("适合开展尊老爱老、志愿服务、社会责任教育。");

        SchoolResourceItemVO item = new SchoolResourceItemVO();
        item.setSchoolId(1L);
        item.setResourceId(2L);
        item.setDistanceMeters(1800);
        item.setEducationThemeSummary("可开展敬老爱老与社会责任主题实践活动");
        item.setResource(resource);

        SchoolMapDetailVO detail = new SchoolMapDetailVO();
        detail.setSchool(school);
        detail.setResources(List.of(item));
        detail.setActivityPlans(Collections.emptyList());
        return detail;
    }
}
