package com.redculture.platform.config;

import com.redculture.platform.controller.AgentQaController;
import com.redculture.platform.controller.AiTeachingPlanController;
import com.redculture.platform.service.AgentQaService;
import com.redculture.platform.service.AiTeachingPlanService;
import com.redculture.platform.service.TeachingActivityPlanService;
import com.redculture.platform.service.TeachingPlanFeedbackService;
import com.redculture.platform.vo.AgentQaResponse;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.request.AgentQaRequest;
import com.redculture.platform.vo.request.TeachingPlanGenerateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(useDefaultFilters = false)
@Import({
        AgentAsyncConfiguration.class,
        AgentQaController.class,
        AiTeachingPlanController.class
})
@EnableConfigurationProperties({AgentProperties.class, AppMapProperties.class})
@TestPropertySource(properties = {
        "app.agent.blocking-max-threads=1",
        "app.agent.blocking-queue-capacity=2",
        "app.agent.mvc-async-core-threads=2",
        "app.agent.mvc-async-max-threads=3",
        "app.agent.mvc-async-queue-capacity=7"
})
class AgentAsyncMvcContextTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RequestMappingHandlerAdapter handlerAdapter;

    @Autowired
    @Qualifier("applicationTaskExecutor")
    private ThreadPoolTaskExecutor applicationTaskExecutor;

    @MockBean
    private AgentQaService agentQaService;

    @MockBean
    private AiTeachingPlanService aiTeachingPlanService;

    @MockBean
    private TeachingActivityPlanService teachingActivityPlanService;

    @MockBean
    private TeachingPlanFeedbackService teachingPlanFeedbackService;

    @Test
    void bindsTheBoundedApplicationExecutorToSpringMvcAsyncHandling() {
        Object configuredExecutor = ReflectionTestUtils.getField(handlerAdapter, "taskExecutor");
        Object configuredTimeout = ReflectionTestUtils.getField(handlerAdapter, "asyncRequestTimeout");

        assertInstanceOf(ThreadPoolTaskExecutor.class, configuredExecutor);
        assertSame(applicationTaskExecutor, configuredExecutor);
        assertEquals("mvc-agent-write-", applicationTaskExecutor.getThreadNamePrefix());
        assertEquals(2, applicationTaskExecutor.getCorePoolSize());
        assertEquals(3, applicationTaskExecutor.getMaxPoolSize());
        assertEquals(
                7,
                applicationTaskExecutor.getThreadPoolExecutor().getQueue().remainingCapacity()
        );
        assertEquals(0L, configuredTimeout);
    }

    @Test
    void dispatchesAgentMonoThroughTheConfiguredMvcContext() throws Exception {
        AuthCurrentUserVO user = schoolUser();
        AgentQaResponse response = new AgentQaResponse();
        response.setAnswer("已找到相关资源。");
        when(agentQaService.ask(any(AgentQaRequest.class), eq(user)))
                .thenReturn(Mono.just(response));

        MvcResult pending = mockMvc.perform(post("/api/ai/qa/ask")
                        .requestAttr(AuthContext.CURRENT_USER_ATTRIBUTE, user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"附近有哪些红色资源？\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        pending.getAsyncResult(2_000);

        mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.answer").value("已找到相关资源。"));
    }

    @Test
    void dispatchesAgentFluxInOrderAndPreservesSseHeaders() throws Exception {
        AuthCurrentUserVO user = schoolUser();
        when(agentQaService.stream(any(AgentQaRequest.class), eq(user)))
                .thenReturn(Flux.just(
                        event("run.started", Map.of("runId", "run-1")),
                        event("done", Map.of("runId", "run-1"))
                ));

        MvcResult pending = mockMvc.perform(post("/api/ai/qa/stream")
                        .requestAttr(AuthContext.CURRENT_USER_ATTRIBUTE, user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"question\":\"附近有哪些红色资源？\"}"))
                .andExpect(request().asyncStarted())
                .andExpect(header().string("Cache-Control", "no-cache, no-transform"))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andReturn();
        pending.getAsyncResult(2_000);

        MvcResult completed = mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
        String body = completed.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(body.indexOf("event:run.started") < body.indexOf("event:done"));
    }

    @Test
    void dispatchesTeachingPlanFluxThroughTheSameMvcContext() throws Exception {
        AuthCurrentUserVO user = schoolUser();
        when(aiTeachingPlanService.generatePlanStream(
                any(TeachingPlanGenerateRequest.class),
                eq(user.getAccountId()),
                eq(user.getRoleCode()),
                eq("thread-plan")
        )).thenReturn(Flux.just(
                event("stage", Map.of("stage", "context_ready")),
                event("done", Map.of("threadId", "thread-plan"))
        ));

        MvcResult pending = mockMvc.perform(post("/api/ai/teaching-plans/generate/stream")
                        .requestAttr(AuthContext.CURRENT_USER_ATTRIBUTE, user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {
                                  "schoolId": 1,
                                  "threadId": "thread-plan",
                                  "grade": "四年级",
                                  "theme": "家乡文化"
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        pending.getAsyncResult(2_000);

        MvcResult completed = mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
        String body = completed.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(body.indexOf("event:stage") < body.indexOf("event:done"));
    }

    private static AuthCurrentUserVO schoolUser() {
        AuthCurrentUserVO user = new AuthCurrentUserVO();
        user.setAccountId(17L);
        user.setRoleCode("school_admin");
        user.setSchoolId(1L);
        return user;
    }

    private static ServerSentEvent<Map<String, Object>> event(
            String name,
            Map<String, Object> data) {
        return ServerSentEvent.<Map<String, Object>>builder()
                .event(name)
                .data(data)
                .build();
    }
}
