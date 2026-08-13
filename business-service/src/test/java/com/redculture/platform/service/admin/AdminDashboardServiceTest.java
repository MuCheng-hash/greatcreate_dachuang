package com.redculture.platform.service.admin;

import com.redculture.platform.config.RagProperties;
import com.redculture.platform.mapper.CatalogProjectionTaskMapper;
import com.redculture.platform.mapper.ContentChunkMapper;
import com.redculture.platform.mapper.LocalEduResourceMapper;
import com.redculture.platform.mapper.SchoolMapper;
import com.redculture.platform.mapper.StudentProfileMapper;
import com.redculture.platform.mapper.TeacherProfileMapper;
import com.redculture.platform.mapper.TeachingActivityPlanMapper;
import com.redculture.platform.service.agent.AgentAdminClient;
import com.redculture.platform.service.rag.ChunkVectorStore;
import com.redculture.platform.vo.admin.AdminDashboardOverviewVO;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminDashboardServiceTest {

    @Test
    void returnsPlatformMetricsAndSplitProjectionStatus() {
        Fixtures fixtures = new Fixtures();
        when(fixtures.resources.selectCount(any())).thenReturn(12L);
        when(fixtures.schools.selectCount(any())).thenReturn(3L);
        when(fixtures.teachers.selectCount(any())).thenReturn(8L);
        when(fixtures.students.selectCount(any())).thenReturn(126L);
        when(fixtures.plans.selectCount(any())).thenReturn(5L);
        when(fixtures.chunks.selectCount(any())).thenReturn(40L, 4L, 0L);
        when(fixtures.projectionTasks.selectCount(any())).thenReturn(2L, 1L);
        when(fixtures.agent.observabilitySummary(anyMap())).thenReturn(
                Mono.just(Map.of("completedQuestionCount", 27))
        );
        when(fixtures.vectorStore.resolveAlias("red_culture_content_chunks_active")).thenReturn("red_culture_content_chunks_v2");
        when(fixtures.vectorStore.listPointIds("red_culture_content_chunks_v2")).thenReturn(Set.of(1L, 2L, 3L));

        AdminDashboardOverviewVO result = fixtures.service().overview().block();

        assertEquals(12L, result.getResourceCount());
        assertEquals(27L, result.getQuestionCount());
        assertEquals("ok", result.getRagStatus().get("status"));
        assertEquals("red_culture_content_chunks_v2", result.getRagStatus().get("collection"));
        assertEquals(3, result.getRagStatus().get("pointCount"));
        assertEquals(3L, result.getPendingProjectionCount());
        assertEquals(2L, result.getProjectionStatus().get("pending"));
        assertEquals(1L, result.getProjectionStatus().get("failed"));
        verify(fixtures.agent).observabilitySummary(Map.of("includeQuestionMetrics", "true"));
    }

    @Test
    void keepsDatabaseMetricsWhenAgentIsUnavailable() {
        Fixtures fixtures = new Fixtures();
        when(fixtures.agent.observabilitySummary(anyMap())).thenReturn(
                Mono.error(new IllegalStateException("agent unavailable"))
        );
        when(fixtures.vectorStore.resolveAlias("red_culture_content_chunks_active")).thenReturn("");
        when(fixtures.vectorStore.listPointIds("red_culture_content_chunks")).thenReturn(Set.of());

        AdminDashboardOverviewVO result = fixtures.service().overview().block();

        assertEquals(0L, result.getSchoolCount());
        assertNull(result.getQuestionCount());
        assertEquals("unavailable", result.getQuestionStatus());
        assertEquals("ok", result.getRagStatus().get("status"));
    }

    @Test
    void marksQdrantUnavailableWithoutLosingRagIndexCounts() {
        Fixtures fixtures = new Fixtures();
        when(fixtures.agent.observabilitySummary(anyMap())).thenReturn(
                Mono.just(Map.of("calls", 4))
        );
        when(fixtures.vectorStore.resolveAlias("red_culture_content_chunks_active"))
                .thenThrow(new IllegalStateException("qdrant unavailable"));

        AdminDashboardOverviewVO result = fixtures.service().overview().block();

        assertEquals(4L, result.getQuestionCount());
        assertEquals(40L, result.getRagStatus().get("done"));
        assertEquals(false, result.getRagStatus().get("reachable"));
        assertEquals("unavailable", result.getRagStatus().get("status"));
        assertTrue(String.valueOf(result.getRagStatus().get("message")).contains("qdrant"));
    }

    private static final class Fixtures {
        private final LocalEduResourceMapper resources = mock(LocalEduResourceMapper.class);
        private final SchoolMapper schools = mock(SchoolMapper.class);
        private final TeacherProfileMapper teachers = mock(TeacherProfileMapper.class);
        private final StudentProfileMapper students = mock(StudentProfileMapper.class);
        private final TeachingActivityPlanMapper plans = mock(TeachingActivityPlanMapper.class);
        private final ContentChunkMapper chunks = mock(ContentChunkMapper.class);
        private final CatalogProjectionTaskMapper projectionTasks = mock(CatalogProjectionTaskMapper.class);
        private final AgentAdminClient agent = mock(AgentAdminClient.class);
        private final ChunkVectorStore vectorStore = mock(ChunkVectorStore.class);
        private final RagProperties ragProperties = new RagProperties();

        private Fixtures() {
            when(resources.selectCount(any())).thenReturn(0L);
            when(schools.selectCount(any())).thenReturn(0L);
            when(teachers.selectCount(any())).thenReturn(0L);
            when(students.selectCount(any())).thenReturn(0L);
            when(plans.selectCount(any())).thenReturn(0L);
            when(chunks.selectCount(any())).thenReturn(40L, 4L, 0L);
            when(projectionTasks.selectCount(any())).thenReturn(0L, 0L);
        }

        private AdminDashboardService service() {
            ragProperties.setEnabled(true);
            return new AdminDashboardService(resources, schools, teachers, students, plans, chunks,
                    projectionTasks, agent, ragProperties, vectorStore);
        }
    }
}
