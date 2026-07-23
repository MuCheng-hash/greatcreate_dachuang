package com.redculture.platform.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.service.AuthService;
import com.redculture.platform.service.KnowledgeRetriever;
import com.redculture.platform.service.SchoolMapService;
import com.redculture.platform.service.TeachingActivityPlanService;
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
    void proxiesTokensAndReturnsValidatedFinalResult() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/llm/teaching-plan/generate/stream", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String events = "event: meta\ndata: {\"promptVersion\":\"v2\",\"promptRunId\":\"run-1\"}\n\n"
                    + "event: token\ndata: {\"delta\":\"主模型残片\"}\n\n"
                    + "event: fallback\ndata: {\"reset\":true,\"nextModel\":\"qwen3:8b\"}\n\n"
                    + "event: token\ndata: {\"delta\":\"第一段\"}\n\n"
                    + "event: result\ndata: {\"generationStatus\":\"completed\",\"message\":\"done\","
                    + "\"theme\":\"家乡文化\",\"grade\":\"四年级\",\"citations\":[],"
                    + "\"promptVersion\":\"v2\",\"promptRunId\":\"run-1\"}\n\n"
                    + "event: done\ndata: {\"promptRunId\":\"run-1\"}\n\n";
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
                    schoolMapService, plans, knowledgeRetriever, properties, new ObjectMapper());

            AuthService authService = mock(AuthService.class);
            AuthCurrentUserVO user = new AuthCurrentUserVO();
            user.setAccountId(17L);
            user.setRoleCode("platform_admin");
            when(authService.currentUser(any())).thenReturn(user);
            MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new AiTeachingPlanController(service, authService, plans)).build();

            MvcResult pending = mvc.perform(post("/api/ai/teaching-plans/generate/stream")
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM)
                            .content("""
                                    {"schoolId":1,"grade":"四年级","theme":"家乡文化",
                                     "activityType":"CLASSROOM","durationMinutes":40,"practiceRequired":false}
                                    """))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            pending.getAsyncResult(5_000);

            mvc.perform(asyncDispatch(pending))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("event:token")))
                    .andExpect(content().string(containsString("event:fallback")))
                    .andExpect(content().string(containsString("\\u7B2C\\u4E00\\u6BB5")))
                    .andExpect(content().string(containsString("event:result")))
                    .andExpect(content().string(containsString("\"promptVersion\":\"v2\"")))
                    .andExpect(content().string(containsString("\"retrievalStatus\":\"ok\"")));
            assertTrue(requestBody.get().contains("\"accountId\":17"));
            assertTrue(requestBody.get().contains("\"sessionId\":"));
        } finally {
            llmServer.stop(0);
        }
    }
}
