package com.redculture.platform.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.exception.TeachingPlanFeedbackException;
import com.redculture.platform.service.AiTeachingPlanService;
import com.redculture.platform.service.TeachingActivityPlanService;
import com.redculture.platform.service.TeachingPlanFeedbackService;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.TeachingPlanFeedbackVO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TeachingPlanFeedbackControllerTest {

    @Test
    void generationHistoryPassesFeedbackStatusFilter() throws Exception {
        TeachingPlanFeedbackService feedbackService = mock(TeachingPlanFeedbackService.class);
        when(feedbackService.mine(any(), eq("submitted"), eq(2L), eq(10L)))
                .thenReturn(PageResult.of(java.util.List.of(), 0L, 2L, 10L));
        MockMvc mvc = mvc(feedbackService);

        mvc.perform(get("/api/ai/teaching-plans/generations/mine")
                        .param("feedbackStatus", "submitted")
                        .param("pageNum", "2")
                        .param("pageSize", "10")
                        .requestAttr(AuthContext.CURRENT_USER_ATTRIBUTE, teacher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(feedbackService).mine(any(), eq("submitted"), eq(2L), eq(10L));
    }

    @Test
    void teacherCanSubmitFeedbackForOwnedGeneration() throws Exception {
        TeachingPlanFeedbackService feedbackService = mock(TeachingPlanFeedbackService.class);
        TeachingPlanFeedbackVO feedback = new TeachingPlanFeedbackVO();
        feedback.setGenerationId(31L);
        feedback.setAdopted(true);
        feedback.setRating(5);
        feedback.setSavedPlanId(88L);
        when(feedbackService.submitFeedback(eq(31L), any(), any())).thenReturn(feedback);
        MockMvc mvc = mvc(feedbackService);

        mvc.perform(put("/api/ai/teaching-plans/generations/31/feedback")
                        .requestAttr(AuthContext.CURRENT_USER_ATTRIBUTE, teacher())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(
                                java.util.Map.of("adopted", true, "rating", 5, "teacherNote", "可使用"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.savedPlanId").value(88));
    }

    @Test
    void ownershipFailureUsesHttpForbidden() throws Exception {
        TeachingPlanFeedbackService feedbackService = mock(TeachingPlanFeedbackService.class);
        when(feedbackService.submitFeedback(eq(31L), any(), any()))
                .thenThrow(new TeachingPlanFeedbackException(
                        HttpStatus.FORBIDDEN, "generation_owner_required", "只能操作本人生成的教学方案"));
        MockMvc mvc = mvc(feedbackService);

        mvc.perform(put("/api/ai/teaching-plans/generations/31/feedback")
                        .requestAttr(AuthContext.CURRENT_USER_ATTRIBUTE, teacher())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adopted\":false,\"rating\":2}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    private MockMvc mvc(TeachingPlanFeedbackService feedbackService) {
        return MockMvcBuilders.standaloneSetup(new AiTeachingPlanController(
                mock(AiTeachingPlanService.class),
                mock(TeachingActivityPlanService.class),
                feedbackService)).build();
    }

    private AuthCurrentUserVO teacher() {
        AuthCurrentUserVO user = new AuthCurrentUserVO();
        user.setAccountId(9L);
        user.setRoleCode("teacher");
        user.setSchoolId(1L);
        return user;
    }
}
