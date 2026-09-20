package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.config.AgentProperties;
import com.redculture.platform.service.AgentToolService;
import com.redculture.platform.service.admin.RagWebSourceService;
import com.redculture.platform.vo.ai.AgentToolRequest;
import com.redculture.platform.vo.ai.KnowledgeRetrieveResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentToolControllerTest {

    @Test
    void rejectsMissingOrInvalidServiceToken() {
        AgentProperties properties = new AgentProperties();
        properties.setInternalServiceToken("secret");
        AgentToolService service = mock(AgentToolService.class);
        AgentToolController controller = new AgentToolController(properties, service, mock(RagWebSourceService.class));

        ApiResponse<?> response = controller.knowledgeRetrieve("wrong", null, null, new AgentToolRequest());

        assertEquals(403, response.getCode());
        verifyNoInteractions(service);
    }

    @Test
    void acceptsValidServiceTokenAndDelegates() {
        AgentProperties properties = new AgentProperties();
        properties.setInternalServiceToken("secret");
        properties.setToolContextSigningSecret("unit-test-secret");
        AgentToolService service = mock(AgentToolService.class);
        when(service.knowledgeRetrieve(org.mockito.ArgumentMatchers.any()))
                .thenReturn(KnowledgeRetrieveResult.empty());
        AgentToolController controller = new AgentToolController(properties, service, mock(RagWebSourceService.class));

        com.redculture.platform.vo.AuthCurrentUserVO user = new com.redculture.platform.vo.AuthCurrentUserVO();
        user.setAccountId(1L);
        user.setRoleCode("school_admin");
        user.setSchoolId(1L);
        String toolContext = new com.redculture.platform.service.agent.AgentToolContextAuthorization(properties)
                .issue(new com.redculture.platform.service.agent.AgentToolContextAuthorization.Context(
                        user, com.redculture.platform.vo.ai.KnowledgeScopeType.SCHOOL, 1L,
                        "turn-1", java.util.List.of("retrieve_knowledge"), 8L, 9L));
        AgentToolRequest forgedRequest = new AgentToolRequest();
        com.redculture.platform.vo.ai.AgentActorVO forgedActor = new com.redculture.platform.vo.ai.AgentActorVO();
        forgedActor.setAccountId(99L);
        forgedActor.setRoleCode("platform_admin");
        forgedRequest.setActor(forgedActor);
        forgedRequest.setResourceId(999L);
        ApiResponse<KnowledgeRetrieveResult> response = controller.knowledgeRetrieve(
                "secret", toolContext, "turn-1", forgedRequest);

        assertEquals(200, response.getCode());
        ArgumentCaptor<AgentToolRequest> captured = ArgumentCaptor.forClass(AgentToolRequest.class);
        verify(service).knowledgeRetrieve(captured.capture());
        assertEquals(1L, captured.getValue().getActor().getAccountId());
        assertEquals("school_admin", captured.getValue().getActor().getRoleCode());
        assertEquals(1L, captured.getValue().getScope().getScopeId());
        assertEquals(8L, captured.getValue().getTaskId());
        assertEquals(9L, captured.getValue().getResourceId());
    }

    @Test
    void rejectsTamperedOrDifferentTurnToolContext() {
        AgentProperties properties = new AgentProperties();
        properties.setInternalServiceToken("secret");
        properties.setToolContextSigningSecret("unit-test-secret");
        AgentToolService service = mock(AgentToolService.class);
        AgentToolController controller = new AgentToolController(properties, service, mock(RagWebSourceService.class));

        com.redculture.platform.vo.AuthCurrentUserVO user = new com.redculture.platform.vo.AuthCurrentUserVO();
        user.setAccountId(1L);
        user.setRoleCode("student");
        user.setSchoolId(1L);
        String context = new com.redculture.platform.service.agent.AgentToolContextAuthorization(properties)
                .issue(new com.redculture.platform.service.agent.AgentToolContextAuthorization.Context(
                        user, com.redculture.platform.vo.ai.KnowledgeScopeType.SCHOOL, 1L,
                        "turn-1", java.util.List.of("retrieve_knowledge"), 8L, 9L));

        assertEquals(403, controller.knowledgeRetrieve("secret", context + "x", "turn-1", new AgentToolRequest()).getCode());
        assertEquals(403, controller.knowledgeRetrieve("secret", context, "turn-2", new AgentToolRequest()).getCode());
        verifyNoInteractions(service);
    }

    @Test
    void healthCheckRequiresServiceTokenAndReportsUp() {
        AgentProperties properties = new AgentProperties();
        properties.setInternalServiceToken("secret");
        AgentToolController controller = new AgentToolController(
                properties, mock(AgentToolService.class), mock(RagWebSourceService.class)
        );

        assertEquals(403, controller.health("wrong").getCode());
        ApiResponse<?> response = controller.health("secret");
        assertEquals(200, response.getCode());
        assertEquals("up", ((java.util.Map<?, ?>) response.getData()).get("status"));
    }

    @Test
    void exposesEnabledWebDomainsOnlyToValidInternalService() {
        AgentProperties properties = new AgentProperties();
        properties.setInternalServiceToken("secret");
        RagWebSourceService sourceService = mock(RagWebSourceService.class);
        when(sourceService.enabledDomains()).thenReturn(java.util.List.of("www.gov.cn"));
        AgentToolController controller = new AgentToolController(
                properties, mock(AgentToolService.class), sourceService
        );

        assertEquals(403, controller.webSourceDomains("wrong").getCode());
        ApiResponse<?> response = controller.webSourceDomains("secret");

        assertEquals(200, response.getCode());
        assertEquals(java.util.List.of("www.gov.cn"),
                ((java.util.Map<?, ?>) response.getData()).get("domains"));
        verify(sourceService).enabledDomains();
    }
}
