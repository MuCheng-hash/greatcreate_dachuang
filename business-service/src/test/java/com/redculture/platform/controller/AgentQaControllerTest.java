package com.redculture.platform.controller;

import com.redculture.platform.service.AgentQaService;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.vo.AgentIntent;
import com.redculture.platform.vo.AgentQaResponse;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.ai.KnowledgeRetrievalStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentQaControllerTest {

    @Test
    void exposesAgentQaResponseThroughUnifiedApiResponse() throws Exception {
        AgentQaService agentQaService = mock(AgentQaService.class);
        AuthCurrentUserVO user = new AuthCurrentUserVO();
        user.setRoleCode("school_admin");
        user.setSchoolId(1L);

        AgentQaResponse response = new AgentQaResponse();
        response.setAnswer("已找到相关资源。");
        response.setIntent(AgentIntent.NEARBY_RESOURCE);
        response.setRetrievalStatus(KnowledgeRetrievalStatus.OK);
        when(agentQaService.ask(any(), eq(user))).thenReturn(response);

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AgentQaController(agentQaService)).build();

        mockMvc.perform(post("/api/ai/qa/ask")
                        .requestAttr(AuthContext.CURRENT_USER_ATTRIBUTE, user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"附近有哪些红色资源？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.answer").value("已找到相关资源。"))
                .andExpect(jsonPath("$.data.intent").value("NEARBY_RESOURCE"))
                .andExpect(jsonPath("$.data.retrievalStatus").value("ok"));
    }

    @Test
    void exposesPersistedAgentThreadHistoryThroughAuthenticatedApi() throws Exception {
        AgentQaService agentQaService = mock(AgentQaService.class);
        AuthCurrentUserVO user = new AuthCurrentUserVO();
        user.setRoleCode("school_admin");
        user.setSchoolId(1L);

        Map<String, Object> history = Map.of(
                "threadId", "thread-1",
                "messages", List.of(Map.of("role", "user", "content", "历史问题"))
        );
        when(agentQaService.getThreadHistory("thread-1", user, "SCHOOL", 1L)).thenReturn(history);

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AgentQaController(agentQaService)).build();

        mockMvc.perform(get("/api/ai/qa/thread/thread-1")
                        .param("scopeType", "SCHOOL")
                        .param("scopeId", "1")
                        .requestAttr(AuthContext.CURRENT_USER_ATTRIBUTE, user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.threadId").value("thread-1"))
                .andExpect(jsonPath("$.data.messages[0].content").value("历史问题"));
    }
}
