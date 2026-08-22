package com.redculture.platform.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.entity.AiTeachingPlanGeneration;
import com.redculture.platform.entity.TeachingPlanFeedback;
import com.redculture.platform.enums.ActivityType;
import com.redculture.platform.exception.TeachingPlanFeedbackException;
import com.redculture.platform.mapper.AiTeachingPlanGenerationMapper;
import com.redculture.platform.mapper.TeachingPlanFeedbackMapper;
import com.redculture.platform.service.TeachingActivityPlanService;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.GeneratedTeachingPlanResponse;
import com.redculture.platform.vo.TeachingActivityPlanAdminVO;
import com.redculture.platform.vo.TeachingPlanFeedbackReportItemVO;
import com.redculture.platform.vo.TeachingPlanFeedbackReasonCountVO;
import com.redculture.platform.vo.TeachingPlanFeedbackReportSummaryVO;
import com.redculture.platform.vo.TeachingPlanFeedbackReportVO;
import com.redculture.platform.vo.TeachingPlanFeedbackVO;
import com.redculture.platform.vo.request.TeachingPlanFeedbackRequest;
import com.redculture.platform.vo.request.TeachingPlanGenerateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeachingPlanFeedbackServiceImplTest {

    private AiTeachingPlanGenerationMapper generationMapper;
    private TeachingPlanFeedbackMapper feedbackMapper;
    private TeachingActivityPlanService planService;
    private TeachingPlanFeedbackServiceImpl service;

    @BeforeEach
    void setUp() {
        generationMapper = mock(AiTeachingPlanGenerationMapper.class);
        feedbackMapper = mock(TeachingPlanFeedbackMapper.class);
        planService = mock(TeachingActivityPlanService.class);
        service = new TeachingPlanFeedbackServiceImpl(
                generationMapper, feedbackMapper, planService, new ObjectMapper());
    }

    @Test
    void recordsOnlySanitizedTeachingPlanSnapshot() {
        doAnswer(invocation -> {
            AiTeachingPlanGeneration value = invocation.getArgument(0);
            value.setGenerationId(17L);
            return 1;
        }).when(generationMapper).insert(any(AiTeachingPlanGeneration.class));
        TeachingPlanGenerateRequest request = new TeachingPlanGenerateRequest();
        request.setSchoolId(1L);
        request.setGrade("四年级");
        request.setTheme("家乡文化");
        request.setActivityType(ActivityType.CLASSROOM);
        request.setModelId("model-public-id");
        GeneratedTeachingPlanResponse response = response();

        Long generationId = service.recordGeneration(request, response, 9L, "teacher");

        assertEquals(17L, generationId);
        ArgumentCaptor<AiTeachingPlanGeneration> captor = ArgumentCaptor.forClass(AiTeachingPlanGeneration.class);
        verify(generationMapper).insert(captor.capture());
        AiTeachingPlanGeneration stored = captor.getValue();
        assertTrue(stored.getRequestJson().contains("model-public-id"));
        assertTrue(stored.getResponseJson().contains("认识家乡文化"));
        assertFalse(stored.getResponseJson().contains("memoryCandidates"));
        assertFalse(stored.getResponseJson().contains("memoryApplied"));
    }

    @Test
    void adoptionCreatesExactlyOneDraftAndLinksIt() {
        AiTeachingPlanGeneration generation = generation(31L, 9L, "teacher");
        when(generationMapper.selectByIdForUpdate(31L)).thenReturn(generation);
        when(feedbackMapper.selectByGenerationIdForUpdate(31L)).thenReturn(null);
        TeachingActivityPlanAdminVO savedPlan = new TeachingActivityPlanAdminVO();
        savedPlan.setPlanId(88L);
        when(planService.createPlan(any())).thenReturn(savedPlan);
        doAnswer(invocation -> {
            TeachingPlanFeedback feedback = invocation.getArgument(0);
            feedback.setFeedbackId(41L);
            return 1;
        }).when(feedbackMapper).insert(any(TeachingPlanFeedback.class));

        TeachingPlanFeedbackRequest request = feedbackRequest(true, 5, "可直接使用");
        TeachingPlanFeedbackVO result = service.submitFeedback(31L, request, teacher(9L));

        assertEquals(88L, result.getSavedPlanId());
        assertEquals(88L, generation.getSavedPlanId());
        verify(planService).createPlan(any());
        verify(generationMapper).updateById(generation);
        verify(feedbackMapper).insert(any(TeachingPlanFeedback.class));
    }

    @Test
    void identicalRepeatedFeedbackIsIdempotent() {
        AiTeachingPlanGeneration generation = generation(31L, 9L, "teacher");
        generation.setSavedPlanId(88L);
        TeachingPlanFeedback existing = new TeachingPlanFeedback();
        existing.setFeedbackId(41L);
        existing.setGenerationId(31L);
        existing.setTeacherAccountId(9L);
        existing.setAdopted(true);
        existing.setRating(4);
        existing.setTeacherNote("原备注");
        existing.setReasonCodesJson("[]");
        when(generationMapper.selectByIdForUpdate(31L)).thenReturn(generation);
        when(feedbackMapper.selectByGenerationIdForUpdate(31L)).thenReturn(existing);

        TeachingPlanFeedbackVO result = service.submitFeedback(
                31L, feedbackRequest(true, 4, "原备注"), teacher(9L));

        assertEquals(4, result.getRating());
        assertEquals("原备注", result.getTeacherNote());
        verify(planService, never()).createPlan(any());
        verify(feedbackMapper, never()).updateById(any(TeachingPlanFeedback.class));
    }

    @Test
    void differentRepeatedFeedbackReturnsConflict() {
        AiTeachingPlanGeneration generation = generation(31L, 9L, "teacher");
        TeachingPlanFeedback existing = new TeachingPlanFeedback();
        existing.setFeedbackId(41L);
        existing.setGenerationId(31L);
        existing.setTeacherAccountId(9L);
        existing.setAdopted(false);
        existing.setRating(2);
        when(generationMapper.selectByIdForUpdate(31L)).thenReturn(generation);
        when(feedbackMapper.selectByGenerationIdForUpdate(31L)).thenReturn(existing);
        TeachingPlanFeedbackException error = assertThrows(TeachingPlanFeedbackException.class,
                () -> service.submitFeedback(31L, feedbackRequest(true, 4, "补充后采纳"), teacher(9L)));

        assertEquals("feedback_already_submitted", error.getCode());
        assertEquals(409, error.getStatus().value());
        verify(planService, never()).createPlan(any());
        verify(feedbackMapper, never()).updateById(any(TeachingPlanFeedback.class));
    }

    @Test
    void manualSaveReusesDraftAlreadyLinkedToGeneration() {
        AiTeachingPlanGeneration generation = generation(31L, 9L, "teacher");
        generation.setSavedPlanId(88L);
        TeachingActivityPlanAdminVO savedPlan = new TeachingActivityPlanAdminVO();
        savedPlan.setPlanId(88L);
        when(generationMapper.selectByIdForUpdate(31L)).thenReturn(generation);
        when(planService.getPlanAdminDetail(88L)).thenReturn(savedPlan);

        TeachingActivityPlanAdminVO result = service.saveDraftForGeneration(31L, 9L, null);

        assertEquals(88L, result.getPlanId());
        verify(planService, never()).createPlan(any());
    }

    @Test
    void adoptedFeedbackCannotBeReverted() {
        AiTeachingPlanGeneration generation = generation(31L, 9L, "teacher");
        generation.setSavedPlanId(88L);
        TeachingPlanFeedback existing = new TeachingPlanFeedback();
        existing.setGenerationId(31L);
        existing.setAdopted(true);
        when(generationMapper.selectByIdForUpdate(31L)).thenReturn(generation);
        when(feedbackMapper.selectByGenerationIdForUpdate(31L)).thenReturn(existing);

        TeachingPlanFeedbackException error = assertThrows(TeachingPlanFeedbackException.class,
                () -> service.submitFeedback(31L, feedbackRequest(false, 2, null), teacher(9L)));

        assertEquals("feedback_already_submitted", error.getCode());
        assertEquals(409, error.getStatus().value());
    }

    @Test
    void anotherTeacherCannotFeedbackOnGeneration() {
        when(generationMapper.selectByIdForUpdate(31L)).thenReturn(generation(31L, 9L, "teacher"));

        TeachingPlanFeedbackException error = assertThrows(TeachingPlanFeedbackException.class,
                () -> service.submitFeedback(31L, feedbackRequest(false, 2, null), teacher(10L)));

        assertEquals(403, error.getStatus().value());
        verify(feedbackMapper, never()).insert(any(TeachingPlanFeedback.class));
    }

    @Test
    void validatesRatingAndNote() {
        TeachingPlanFeedbackException rating = assertThrows(TeachingPlanFeedbackException.class,
                () -> service.submitFeedback(31L, feedbackRequest(true, 6, null), teacher(9L)));
        assertEquals("rating_out_of_range", rating.getCode());

        TeachingPlanFeedbackException note = assertThrows(TeachingPlanFeedbackException.class,
                () -> service.submitFeedback(31L, feedbackRequest(true, 5, "x".repeat(2001)), teacher(9L)));
        assertEquals("teacher_note_too_long", note.getCode());
        verify(generationMapper, never()).selectByIdForUpdate(anyLong());
    }

    @Test
    void validatesAndDeduplicatesNegativeReasonCodes() {
        when(generationMapper.selectByIdForUpdate(31L)).thenReturn(generation(31L, 9L, "teacher"));
        when(feedbackMapper.selectByGenerationIdForUpdate(31L)).thenReturn(null);
        TeachingPlanFeedbackRequest request = feedbackRequest(false, 2, "需要调整");
        request.setReasonCodes(List.of("OTHER", "GRADE_MISMATCH", "OTHER"));

        service.submitFeedback(31L, request, teacher(9L));

        ArgumentCaptor<TeachingPlanFeedback> captor = ArgumentCaptor.forClass(TeachingPlanFeedback.class);
        verify(feedbackMapper).insert(captor.capture());
        assertEquals("[\"GRADE_MISMATCH\",\"OTHER\"]", captor.getValue().getReasonCodesJson());
    }

    @Test
    void rejectsUnknownPositiveAndOtherWithoutNoteReasons() {
        TeachingPlanFeedbackRequest unknown = feedbackRequest(false, 2, null);
        unknown.setReasonCodes(List.of("UNKNOWN"));
        assertEquals("invalid_reason_code", assertThrows(TeachingPlanFeedbackException.class,
                () -> service.submitFeedback(31L, unknown, teacher(9L))).getCode());

        TeachingPlanFeedbackRequest positive = feedbackRequest(true, 4, null);
        positive.setReasonCodes(List.of("GRADE_MISMATCH"));
        assertEquals("reason_codes_not_applicable", assertThrows(TeachingPlanFeedbackException.class,
                () -> service.submitFeedback(31L, positive, teacher(9L))).getCode());

        TeachingPlanFeedbackRequest other = feedbackRequest(false, 5, "   ");
        other.setReasonCodes(List.of("OTHER"));
        assertEquals("other_reason_note_required", assertThrows(TeachingPlanFeedbackException.class,
                () -> service.submitFeedback(31L, other, teacher(9L))).getCode());
        verify(generationMapper, never()).selectByIdForUpdate(anyLong());
    }

    @Test
    void calculatesReportRatesAndRatingDistribution() {
        TeachingPlanFeedbackReportSummaryVO summary = new TeachingPlanFeedbackReportSummaryVO();
        summary.setGenerationCount(10);
        summary.setFeedbackCount(8);
        summary.setAdoptedCount(6);
        summary.setNotAdoptedCount(2);
        summary.setAverageRating(new BigDecimal("3.625"));
        summary.setRatingOneCount(1);
        summary.setRatingTwoCount(1);
        summary.setRatingThreeCount(2);
        summary.setRatingFourCount(2);
        summary.setRatingFiveCount(2);
        when(generationMapper.selectReportSummary(any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(summary);
        when(generationMapper.countReportRows(any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(10L);
        TeachingPlanFeedbackReportItemVO item = new TeachingPlanFeedbackReportItemVO();
        item.setGenerationId(1L);
        item.setResponseJson("{\"theme\":\"家乡文化\"}");
        item.setReasonCodesJson("[\"GRADE_MISMATCH\",\"OTHER\"]");
        when(generationMapper.selectReportRows(any(), any(), any(), any(), any(), any(), anyBoolean(), any(), anyLong(), anyLong()))
                .thenReturn(List.of(item));
        TeachingPlanFeedbackReasonCountVO reasonCount = new TeachingPlanFeedbackReasonCountVO();
        reasonCount.setReasonCode("GRADE_MISMATCH");
        reasonCount.setReasonCount(3L);
        when(generationMapper.selectReasonCounts(any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(List.of(reasonCount));
        when(generationMapper.countUnspecifiedReasons(any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(2L);

        TeachingPlanFeedbackReportVO report = service.report(
                null, null, null, null, null, null, false, null, 1L, 20L);

        assertEquals(new BigDecimal("80.00"), report.getFeedbackRate());
        assertEquals(new BigDecimal("75.00"), report.getAdoptionRate());
        assertEquals(new BigDecimal("3.63"), report.getAverageRating());
        assertEquals(2L, report.getRatingDistribution().get(5));
        assertEquals(3L, report.getReasonDistribution().get("GRADE_MISMATCH"));
        assertEquals(2L, report.getReasonDistribution().get("UNSPECIFIED"));
        assertEquals(List.of("GRADE_MISMATCH", "OTHER"), report.getDetails().getRecords().get(0).getReasonCodes());
        assertNotNull(report.getDetails().getRecords().get(0).getPlan());
    }

    @Test
    void reportReturnsZeroRatesWhenThereAreNoTeacherGenerations() {
        when(generationMapper.selectReportSummary(any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new TeachingPlanFeedbackReportSummaryVO());
        when(generationMapper.countReportRows(any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(0L);
        when(generationMapper.selectReportRows(any(), any(), any(), any(), any(), any(), anyBoolean(), any(), anyLong(), anyLong()))
                .thenReturn(List.of());

        TeachingPlanFeedbackReportVO report = service.report(
                null, null, null, null, null, null, false, null, null, null);

        assertEquals(new BigDecimal("0.00"), report.getFeedbackRate());
        assertEquals(new BigDecimal("0.00"), report.getAdoptionRate());
        assertEquals(new BigDecimal("0.00"), report.getAverageRating());
        assertEquals(20L, report.getDetails().getPageSize());
    }

    private GeneratedTeachingPlanResponse response() {
        GeneratedTeachingPlanResponse response = new GeneratedTeachingPlanResponse();
        response.setGenerationStatus("completed");
        response.setRetrievalStatus("ok");
        response.setTheme("家乡文化");
        response.setGrade("四年级");
        response.setActivityType("classroom");
        response.setDurationMinutes(40);
        response.setObjectives(List.of("认识家乡文化"));
        response.setActivityFlow(List.of("阅读材料", "小组展示"));
        return response;
    }

    private AiTeachingPlanGeneration generation(Long generationId, Long accountId, String actorRole) {
        AiTeachingPlanGeneration generation = new AiTeachingPlanGeneration();
        generation.setGenerationId(generationId);
        generation.setSchoolId(1L);
        generation.setAccountId(accountId);
        generation.setActorRole(actorRole);
        generation.setTheme("家乡文化");
        generation.setGrade("四年级");
        generation.setActivityType("classroom");
        generation.setResponseJson("""
                {"generationStatus":"completed","theme":"家乡文化","grade":"四年级",
                 "activityType":"classroom","durationMinutes":40,
                 "objectives":["认识家乡文化"],"activityFlow":["阅读材料","小组展示"]}
                """);
        generation.setCreatedAt(LocalDateTime.now());
        return generation;
    }

    private AuthCurrentUserVO teacher(Long accountId) {
        AuthCurrentUserVO user = new AuthCurrentUserVO();
        user.setAccountId(accountId);
        user.setRoleCode("teacher");
        user.setSchoolId(1L);
        return user;
    }

    private TeachingPlanFeedbackRequest feedbackRequest(boolean adopted, int rating, String note) {
        TeachingPlanFeedbackRequest request = new TeachingPlanFeedbackRequest();
        request.setAdopted(adopted);
        request.setRating(rating);
        request.setTeacherNote(note);
        return request;
    }
}
