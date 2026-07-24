package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.config.AgentProperties;
import com.redculture.platform.service.AgentToolService;
import com.redculture.platform.vo.ai.AgentToolRequest;
import com.redculture.platform.vo.ai.KnowledgeRetrieveResult;
import org.junit.jupiter.api.Test;

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
        AgentToolController controller = new AgentToolController(properties, service);

        ApiResponse<?> response = controller.knowledgeRetrieve("wrong", new AgentToolRequest());

        assertEquals(403, response.getCode());
        verifyNoInteractions(service);
    }

    @Test
    void acceptsValidServiceTokenAndDelegates() {
        AgentProperties properties = new AgentProperties();
        properties.setInternalServiceToken("secret");
        AgentToolService service = mock(AgentToolService.class);
        when(service.knowledgeRetrieve(org.mockito.ArgumentMatchers.any()))
                .thenReturn(KnowledgeRetrieveResult.empty());
        AgentToolController controller = new AgentToolController(properties, service);

        ApiResponse<KnowledgeRetrieveResult> response = controller.knowledgeRetrieve(
                "secret", new AgentToolRequest()
        );

        assertEquals(200, response.getCode());
        verify(service).knowledgeRetrieve(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void healthCheckRequiresServiceTokenAndReportsUp() {
        AgentProperties properties = new AgentProperties();
        properties.setInternalServiceToken("secret");
        AgentToolController controller = new AgentToolController(
                properties, mock(AgentToolService.class)
        );

        assertEquals(403, controller.health("wrong").getCode());
        ApiResponse<?> response = controller.health("secret");
        assertEquals(200, response.getCode());
        assertEquals("up", ((java.util.Map<?, ?>) response.getData()).get("status"));
    }
}
