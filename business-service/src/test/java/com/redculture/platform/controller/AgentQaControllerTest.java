package com.redculture.platform.controller;

import com.redculture.platform.service.AgentQaService;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.vo.AgentIntent;
import com.redculture.platform.vo.AgentQaResponse;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.ai.AssistantConversationTurnCancellation;
import com.redculture.platform.vo.ai.KnowledgeRetrievalStatus;
import com.redculture.platform.vo.ai.AgentActionVO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

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
        when(agentQaService.ask(any(), eq(user))).thenReturn(Mono.just(response));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AgentQaController(agentQaService)).build();

        MvcResult async = mockMvc.perform(post("/api/ai/qa/ask")
                        .requestAttr(AuthContext.CURRENT_USER_ATTRIBUTE, user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"附近有哪些红色资源？\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(async))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.answer").value("已找到相关资源。"))
                .andExpect(jsonPath("$.data.intent").value("NEARBY_RESOURCE"))
                .andExpect(jsonPath("$.data.retrievalStatus").value("ok"));
    }

    @Test
    void disablesProxyBufferingForAgentStream() throws Exception {
        AgentQaService agentQaService = mock(AgentQaService.class);
        AuthCurrentUserVO user = new AuthCurrentUserVO();
        user.setRoleCode("school_admin");
        user.setSchoolId(1L);
        when(agentQaService.stream(any(), eq(user))).thenReturn(Flux.just(
                ServerSentEvent.<Map<String, Object>>builder()
                        .event("done")
                        .data(Map.of())
                        .build()
        ));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AgentQaController(agentQaService)).build();

        MvcResult async = mockMvc.perform(post("/api/ai/qa/stream")
                        .requestAttr(AuthContext.CURRENT_USER_ATTRIBUTE, user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"附近有哪些红色资源？\"}"))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache, no-transform"))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andReturn();
        mockMvc.perform(asyncDispatch(async)).andExpect(status().isOk());
    }

    @Test
    void exposesTurnCancellationForCurrentSchoolUser() throws Exception {
        AgentQaService agentQaService = mock(AgentQaService.class);
        AuthCurrentUserVO user = new AuthCurrentUserVO();
        user.setRoleCode("school_admin");
        user.setSchoolId(1L);
        AssistantConversationTurnCancellation cancellation =
                new AssistantConversationTurnCancellation();
        cancellation.setClientTurnId("turn-client-1");
        cancellation.setThreadId("thread-1");
        cancellation.setTurnStatus("running");
        cancellation.setCancellationRequested(true);
        when(agentQaService.cancelTurn("turn-client-1", user))
                .thenReturn(Mono.just(cancellation));

        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new AgentQaController(agentQaService))
                .build();

        MvcResult async = mockMvc.perform(post("/api/ai/qa/turns/turn-client-1/cancel")
                        .requestAttr(AuthContext.CURRENT_USER_ATTRIBUTE, user))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(async))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clientTurnId").value("turn-client-1"))
                .andExpect(jsonPath("$.data.threadId").value("thread-1"))
                .andExpect(jsonPath("$.data.cancellationRequested").value(true));
    }

    @Test
    void exposesScopedActionQueryAndDecision() throws Exception {
        AgentQaService service = mock(AgentQaService.class);
        AuthCurrentUserVO user = new AuthCurrentUserVO();
        user.setSchoolId(1L);
        AgentActionVO action = new AgentActionVO();
        action.setActionId("action-1");
        action.setStatus("pending_confirmation");
        when(service.getAction("action-1", user)).thenReturn(Mono.just(action));
        when(service.decideAction("action-1", "approve", user)).thenReturn(
                Mono.fromSupplier(() -> {
                    action.setStatus("approved");
                    return action;
                })
        );
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentQaController(service)).build();

        MvcResult getAsync = mvc.perform(get("/api/ai/qa/actions/action-1")
                        .requestAttr(AuthContext.CURRENT_USER_ATTRIBUTE, user))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(getAsync))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("pending_confirmation"));
        MvcResult decisionAsync = mvc.perform(post("/api/ai/qa/actions/action-1/decision")
                        .requestAttr(AuthContext.CURRENT_USER_ATTRIBUTE, user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"approve\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(decisionAsync))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("approved"));
    }

}
