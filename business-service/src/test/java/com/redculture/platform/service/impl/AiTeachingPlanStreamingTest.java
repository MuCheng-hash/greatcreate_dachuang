package com.redculture.platform.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.config.AgentProperties;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.service.KnowledgeRetriever;
import com.redculture.platform.service.SchoolMapService;
import com.redculture.platform.service.TeachingActivityPlanService;
import com.redculture.platform.service.agent.AgentRuntimeClient;
import com.redculture.platform.controller.AiTeachingPlanController;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.SchoolMapDetailVO;
import com.redculture.platform.vo.SchoolSummaryVO;
import com.redculture.platform.vo.ai.KnowledgeRetrieveResult;
import com.redculture.platform.vo.ai.KnowledgeRetrievalStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiTeachingPlanStreamingTest {

    @Test
    void proxiesSafePlanPatchesAndReturnsValidatedFinalResult() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/agent/messages/stream", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String events = "event: run.started\ndata: {\"threadId\":\"thread-1\",\"taskType\":\"TEACHING_PLAN\",\"model\":\"qwen-plus\"}\n\n"
                    + "event: model.started\ndata: {\"model\":\"qwen-plus\"}\n\n"
                    + "event: token\ndata: {\"delta\":\"{\\\"theme\\\":\\\"家乡文化\"}\"}\n\n"
                    + "event: model.failed\ndata: {\"errorType\":\"timeout\",\"nextModel\":\"qwen3:8b\"}\n\n"
                    + "event: plan.patch\ndata: {\"patch\":{\"theme\":\"家乡文化\",\"grade\":\"四年级\","
                    + "\"activityFlow\":[{\"time\":\"0-20分钟\",\"content\":\"校内集合并完成导入\"}],"
                    + "\"llmModel\":\"qwen-plus\",\"provider\":\"openai-compatible\"}}\n\n"
                    + "event: final\ndata: {\"threadId\":\"thread-1\",\"response\":{"
                    + "\"taskType\":\"TEACHING_PLAN\",\"answer\":\"done\",\"status\":\"completed\","
                    + "\"generationStatus\":\"success\",\"teachingPlan\":{"
                    + "\"generationStatus\":\"success\",\"message\":\"done\","
                    + "\"theme\":\"家乡文化\",\"grade\":\"四年级\",\"citations\":[],"
                    + "\"activityFlow\":[{\"time\":\"0-20分钟\",\"content\":\"校内集合并完成导入\"}]}}}\n\n"
                    + "event: done\ndata: {\"threadId\":\"thread-1\"}\n\n";
            byte[] response = events.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        llmServer.start();

        try {
            SchoolMapService schoolMapService = mock(SchoolMapService.class);
            SchoolMapDetailVO detail = new SchoolMapDetailVO();
            SchoolSummaryVO school = new SchoolSummaryVO();
            school.setSchoolId(1L);
            school.setSchoolName("里庄小学");
            detail.setSchool(school);
            when(schoolMapService.getSchoolDetail(1L)).thenReturn(detail);

            KnowledgeRetriever knowledgeRetriever = mock(KnowledgeRetriever.class);
            KnowledgeRetrieveResult retrieval = new KnowledgeRetrieveResult();
            retrieval.setRetrievalStatus(KnowledgeRetrievalStatus.OK);
            when(knowledgeRetriever.retrieve(any())).thenReturn(retrieval);

            AppMapProperties properties = new AppMapProperties();
            properties.setLlmServiceBaseUrl("http://127.0.0.1:" + llmServer.getAddress().getPort());
            TeachingActivityPlanService plans = mock(TeachingActivityPlanService.class);
            AiTeachingPlanServiceImpl service = new AiTeachingPlanServiceImpl(
                    schoolMapService,
                    plans,
                    knowledgeRetriever,
                    properties,
                    new AgentRuntimeClient(properties, new AgentProperties(), new ObjectMapper()),
                    new ObjectMapper());

            AuthCurrentUserVO user = new AuthCurrentUserVO();
            user.setAccountId(17L);
            user.setRoleCode("platform_admin");
            MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new AiTeachingPlanController(service, plans)).build();

            MvcResult pending = mvc.perform(post("/api/ai/teaching-plans/generate/stream")
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM)
                            .requestAttr(AuthContext.CURRENT_USER_ATTRIBUTE, user)
                            .content("""
                                    {"schoolId":1,"grade":"四年级","theme":"家乡文化",
                                     "activityType":"CLASSROOM","durationMinutes":40,"practiceRequired":false}
                                    """))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            pending.getAsyncResult(5_000);

            MvcResult streamingResult = mvc.perform(asyncDispatch(pending))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("event:plan.patch")))
                    .andExpect(content().string(containsString("event:stage")))
                    .andExpect(content().string(containsString("0-20")))
                    .andExpect(content().string(containsString("event:final")))
                    .andExpect(content().string(containsString("\"teachingPlan\"")))
                    .andExpect(content().string(containsString("\"generationStatus\":\"completed\"")))
                    .andExpect(content().string(containsString("\"retrievalStatus\":\"ok\"")))
                    .andReturn();
            String responseBody = streamingResult.getResponse().getContentAsString();
            assertFalse(responseBody.contains("event:token"));
            assertFalse(responseBody.contains("qwen-plus"));
            assertFalse(responseBody.contains("qwen3:8b"));
            assertFalse(responseBody.contains("LLM 服务不可用"));
            assertTrue(requestBody.get().contains("\"ownerId\":\"account:17\""));
            assertTrue(requestBody.get().contains("\"taskType\":\"TEACHING_PLAN\""));
        } finally {
            llmServer.stop(0);
        }
    }
}
