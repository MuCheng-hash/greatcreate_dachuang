package com.redculture.platform.controller;

import com.redculture.platform.service.AgentQaService;
import com.redculture.platform.service.AuthService;
import com.redculture.platform.vo.AgentIntent;
import com.redculture.platform.vo.AgentQaResponse;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.ai.KnowledgeRetrievalStatus;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentQaControllerTest {

    @Test
    void exposesAgentQaResponseThroughUnifiedApiResponse() throws Exception {
        AgentQaService agentQaService = mock(AgentQaService.class);
        AuthService authService = mock(AuthService.class);
        AuthCurrentUserVO user = new AuthCurrentUserVO();
        user.setRoleCode("school_admin");
        user.setSchoolId(1L);
        when(authService.currentUser(any(HttpSession.class))).thenReturn(user);

        AgentQaResponse response = new AgentQaResponse();
        response.setAnswer("已找到相关资源。");
        response.setIntent(AgentIntent.NEARBY_RESOURCE);
        response.setRetrievalStatus(KnowledgeRetrievalStatus.OK);
        when(agentQaService.ask(any(), eq(user))).thenReturn(response);

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AgentQaController(agentQaService, authService)).build();

        mockMvc.perform(post("/api/ai/qa/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"附近有哪些红色资源？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.answer").value("已找到相关资源。"))
                .andExpect(jsonPath("$.data.intent").value("NEARBY_RESOURCE"))
                .andExpect(jsonPath("$.data.retrievalStatus").value("ok"));
    }
}
