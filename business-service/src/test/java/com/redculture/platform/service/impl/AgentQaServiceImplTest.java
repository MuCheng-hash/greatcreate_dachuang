package com.redculture.platform.service.impl;

import com.redculture.platform.service.KnowledgeRetriever;
import com.redculture.platform.service.LocalEduResourceService;
import com.redculture.platform.service.SchoolMapService;
import com.redculture.platform.service.TownMapService;
import com.redculture.platform.service.agent.AnswerGenerator;
import com.redculture.platform.service.agent.AgentRuntimeClient;
import com.redculture.platform.service.agent.AgentRuntimeResult;
import com.redculture.platform.service.agent.CitationValidator;
import com.redculture.platform.service.agent.GeneratedAnswer;
import com.redculture.platform.service.agent.KeywordIntentRecognizer;
import com.redculture.platform.vo.AgentGenerationStatus;
import com.redculture.platform.vo.AgentIntent;
import com.redculture.platform.vo.AgentQaResponse;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.LocalEduResourceSummaryVO;
import com.redculture.platform.vo.SchoolMapDetailVO;
import com.redculture.platform.vo.SchoolResourceItemVO;
import com.redculture.platform.vo.SchoolSummaryVO;
import com.redculture.platform.vo.ai.KnowledgeChunkVO;
import com.redculture.platform.vo.ai.KnowledgeCitationCandidateVO;
import com.redculture.platform.vo.ai.KnowledgeRetrieveRequest;
import com.redculture.platform.vo.ai.KnowledgeRetrieveResult;
import com.redculture.platform.vo.ai.KnowledgeRetrievalStatus;
import com.redculture.platform.vo.request.AgentQaRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentQaServiceImplTest {

    @Test
    void buildsSchoolScopedRetrievalAndKeepsOnlyValidCitations() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieve(any(KnowledgeRetrieveRequest.class))).thenReturn(okResult());
        AgentQaServiceImpl service = newService(retriever, new GeneratedAnswer(
                "回答", List.of("chunk:1", "forged:citation"), List.of("继续追问")));

        AgentQaResponse response = service.ask(request("请介绍这个资源的教育价值。"), schoolUser());

        assertEquals(AgentIntent.RESOURCE_EXPLANATION, response.getIntent());
        assertEquals(KnowledgeRetrievalStatus.OK, response.getRetrievalStatus());
        assertEquals(1, response.getCitations().size());
        assertEquals("chunk:1", response.getCitations().get(0).getCitationId());
        assertFalse(response.getRelatedResources().isEmpty());
        verify(retriever).retrieve(any(KnowledgeRetrieveRequest.class));
    }

    @Test
    void resolvesSchoolNameAndGradeForAdministrator() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieve(any(KnowledgeRetrieveRequest.class))).thenReturn(okResult());
        AgentQaServiceImpl service = newService(retriever, new GeneratedAnswer("回答", List.of(), List.of()));

        AgentQaRequest request = request("里庄小学附近有哪些红色资源？适合四年级去？");
        AgentQaResponse response = service.ask(request, adminUser());

        assertEquals(1L, response.getScopeId());
        assertEquals(AgentGenerationStatus.COMPLETED, response.getGenerationStatus());
        ArgumentCaptor<KnowledgeRetrieveRequest> captor = ArgumentCaptor.forClass(KnowledgeRetrieveRequest.class);
        verify(retriever).retrieve(captor.capture());
        assertEquals(1L, captor.getValue().getScopeId());
        assertEquals("四年级", captor.getValue().getGrade());
    }

    @Test
    void asksForClarificationWhenAdministratorScopeIsAmbiguous() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        AgentQaServiceImpl service = newService(retriever, new GeneratedAnswer("回答", List.of(), List.of()));

        AgentQaResponse response = service.ask(
                request("里庄小学和示例小学附近有哪些红色资源？"),
                adminUser()
        );

        assertTrue(response.isClarificationRequired());
        assertEquals(KnowledgeRetrievalStatus.EMPTY, response.getRetrievalStatus());
        assertEquals(AgentGenerationStatus.SKIPPED, response.getGenerationStatus());
        assertEquals(List.of("里庄小学", "示例小学"), response.getClarificationOptions());
        verify(retriever, never()).retrieve(any());
    }

    @Test
    void rejectsMentionedOtherSchoolForSchoolAccount() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        AgentQaServiceImpl service = newService(retriever, new GeneratedAnswer("回答", List.of(), List.of()));

        assertThrows(IllegalArgumentException.class,
                () -> service.ask(request("示例小学附近有哪些红色资源？"), schoolUser()));
        verify(retriever, never()).retrieve(any());
    }

    @Test
    void returnsEmptyWithoutCallingRagForUnknownIntent() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        AgentQaServiceImpl service = newService(retriever, new GeneratedAnswer("回答", List.of(), List.of()));

        AgentQaResponse response = service.ask(request("今天的天气怎么样？"), schoolUser());

        assertEquals(AgentIntent.UNKNOWN, response.getIntent());
        assertEquals(KnowledgeRetrievalStatus.EMPTY, response.getRetrievalStatus());
        verify(retriever, never()).retrieve(any());
    }

    @Test
    void preservesEmptyRetrievalStatus() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieve(any())).thenReturn(KnowledgeRetrieveResult.empty());
        AgentQaServiceImpl service = newService(retriever, new GeneratedAnswer("回答", List.of(), List.of()));

        AgentQaResponse response = service.ask(request("附近有哪些红色资源？"), schoolUser());

        assertEquals(KnowledgeRetrievalStatus.EMPTY, response.getRetrievalStatus());
    }

    @Test
    void supplementsRealCitationsWhenGeneratorReturnsNone() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieve(any())).thenReturn(okResult());
        AgentQaServiceImpl service = newService(retriever, new GeneratedAnswer("回答", List.of(), List.of()));

        AgentQaResponse response = service.ask(request("附近有哪些红色资源？"), schoolUser());

        assertEquals(1, response.getCitations().size());
        assertEquals("chunk:1", response.getCitations().get(0).getCitationId());
    }

    @Test
    void degradesWhenRagThrows() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieve(any())).thenThrow(new IllegalStateException("neo4j unavailable"));
        AgentQaServiceImpl service = newService(retriever, new GeneratedAnswer("回答", List.of(), List.of()));

        AgentQaResponse response = service.ask(request("附近有哪些红色资源？"), schoolUser());

        assertEquals(KnowledgeRetrievalStatus.DEGRADED, response.getRetrievalStatus());
    }

    @Test
    void rejectsOtherScopeForSchoolAccountBeforeRagCall() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        AgentQaServiceImpl service = newService(retriever, new GeneratedAnswer("回答", List.of(), List.of()));
        AgentQaRequest request = request("附近有哪些资源？");
        request.setScopeType("REGION");
        request.setScopeId(99L);

        assertThrows(IllegalArgumentException.class, () -> service.ask(request, schoolUser()));
        verify(retriever, never()).retrieve(any());
    }

    @Test
    void exposesStatefulRuntimeMetadataWhenFastApiAgentResponds() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieve(any(KnowledgeRetrieveRequest.class))).thenReturn(okResult());
        AgentRuntimeClient runtime = mock(AgentRuntimeClient.class);
        when(runtime.generate(any(), any(), any())).thenReturn(new AgentRuntimeResult(
                new GeneratedAnswer("Agent 回答", List.of("chunk:1"), List.of("继续追问")),
                "thread-1", "completed", List.of("retrieve_knowledge")
        ));
        AgentQaServiceImpl service = newRuntimeService(retriever, runtime);
        AgentQaRequest request = request("请介绍这个资源的教育价值。");
        request.setThreadId("thread-1");

        AgentQaResponse response = service.ask(request, schoolUser());

        assertEquals("thread-1", response.getThreadId());
        assertEquals("completed", response.getStatus());
        assertEquals(List.of("retrieve_knowledge"), response.getToolExecutions());
        assertEquals("Agent 回答", response.getAnswer());
    }

    private AgentQaServiceImpl newService(KnowledgeRetriever retriever, GeneratedAnswer answer) {
        SchoolMapService schoolMapService = mock(SchoolMapService.class);
        when(schoolMapService.getSchoolDetail(1L)).thenReturn(schoolDetail());
        when(schoolMapService.listSchools(null, null, null, 100)).thenReturn(List.of(
                schoolSummary(1L, "里庄小学"),
                schoolSummary(2L, "示例小学")
        ));
        AnswerGenerator answerGenerator = context -> answer;
        return new AgentQaServiceImpl(
                schoolMapService,
                mock(TownMapService.class),
                mock(LocalEduResourceService.class),
                retriever,
                new KeywordIntentRecognizer(),
                answerGenerator,
                new CitationValidator()
        );
    }

    private AgentQaServiceImpl newRuntimeService(KnowledgeRetriever retriever, AgentRuntimeClient runtime) {
        SchoolMapService schoolMapService = mock(SchoolMapService.class);
        when(schoolMapService.getSchoolDetail(1L)).thenReturn(schoolDetail());
        return new AgentQaServiceImpl(
                schoolMapService,
                mock(TownMapService.class),
                mock(LocalEduResourceService.class),
                retriever,
                new KeywordIntentRecognizer(),
                context -> new GeneratedAnswer("fallback", List.of(), List.of()),
                new CitationValidator(),
                runtime
        );
    }

    private AgentQaRequest request(String question) {
        AgentQaRequest request = new AgentQaRequest();
        request.setQuestion(question);
        return request;
    }

    private AuthCurrentUserVO schoolUser() {
        AuthCurrentUserVO user = new AuthCurrentUserVO();
        user.setRoleCode("school_admin");
        user.setSchoolId(1L);
        return user;
    }

    private AuthCurrentUserVO adminUser() {
        AuthCurrentUserVO user = new AuthCurrentUserVO();
        user.setRoleCode("platform_admin");
        return user;
    }

    private SchoolSummaryVO schoolSummary(Long schoolId, String schoolName) {
        SchoolSummaryVO school = new SchoolSummaryVO();
        school.setSchoolId(schoolId);
        school.setSchoolName(schoolName);
        return school;
    }

    private SchoolMapDetailVO schoolDetail() {
        SchoolSummaryVO school = new SchoolSummaryVO();
        school.setSchoolId(1L);
        school.setSchoolName("里庄小学");

        LocalEduResourceSummaryVO resource = new LocalEduResourceSummaryVO();
        resource.setResourceId(2L);
        resource.setResourceName("常安镇敬老院");
        resource.setIntro("可开展敬老志愿服务活动。");
        resource.setEducationValue("适合开展尊老爱老和社会责任教育。");

        SchoolResourceItemVO item = new SchoolResourceItemVO();
        item.setSchoolId(1L);
        item.setResourceId(2L);
        item.setDistanceMeters(1800);
        item.setResource(resource);

        SchoolMapDetailVO detail = new SchoolMapDetailVO();
        detail.setSchool(school);
        detail.setResources(List.of(item));
        detail.setActivityPlans(Collections.emptyList());
        return detail;
    }

    private KnowledgeRetrieveResult okResult() {
        KnowledgeChunkVO chunk = new KnowledgeChunkVO();
        chunk.setCitationId("chunk:1");
        chunk.setChunkId(1L);
        chunk.setTitle("敬老服务资源说明");
        chunk.setText("可组织学生开展尊老爱老和社会责任教育。");
        chunk.setScore(0.91D);

        KnowledgeCitationCandidateVO candidate = new KnowledgeCitationCandidateVO();
        candidate.setCitationId("chunk:1");
        candidate.setTitle(chunk.getTitle());
        candidate.setExcerpt(chunk.getText());
        candidate.setSourceType("content_chunk");

        KnowledgeRetrieveResult result = new KnowledgeRetrieveResult();
        result.setRetrievalStatus(KnowledgeRetrievalStatus.OK);
        result.setChunks(List.of(chunk));
        result.setGraphFacts(Collections.emptyList());
        result.setCitationCandidates(List.of(candidate));
        return result;
    }
}
