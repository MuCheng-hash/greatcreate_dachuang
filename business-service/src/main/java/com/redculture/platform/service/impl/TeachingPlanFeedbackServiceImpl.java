package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.entity.AiTeachingPlanGeneration;
import com.redculture.platform.entity.TeachingPlanFeedback;
import com.redculture.platform.enums.ActivityType;
import com.redculture.platform.enums.TeachingPlanFeedbackReason;
import com.redculture.platform.exception.TeachingPlanFeedbackException;
import com.redculture.platform.mapper.AiTeachingPlanGenerationMapper;
import com.redculture.platform.mapper.TeachingPlanFeedbackMapper;
import com.redculture.platform.service.TeachingActivityPlanService;
import com.redculture.platform.service.TeachingPlanFeedbackService;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.GeneratedTeachingPlanResponse;
import com.redculture.platform.vo.TeachingActivityPlanAdminVO;
import com.redculture.platform.vo.TeachingPlanFeedbackReportItemVO;
import com.redculture.platform.vo.TeachingPlanFeedbackReasonCountVO;
import com.redculture.platform.vo.TeachingPlanFeedbackReportSummaryVO;
import com.redculture.platform.vo.TeachingPlanFeedbackReportVO;
import com.redculture.platform.vo.TeachingPlanFeedbackVO;
import com.redculture.platform.vo.TeachingPlanGenerationVO;
import com.redculture.platform.vo.request.TeachingActivityPlanCreateRequest;
import com.redculture.platform.vo.request.TeachingPlanFeedbackRequest;
import com.redculture.platform.vo.request.TeachingPlanGenerateRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TeachingPlanFeedbackServiceImpl implements TeachingPlanFeedbackService {

    private static final long DEFAULT_PAGE_NUM = 1L;
    private static final long DEFAULT_PAGE_SIZE = 20L;
    private static final long MAX_PAGE_SIZE = 100L;
    private static final int MAX_NOTE_LENGTH = 2000;

    private final AiTeachingPlanGenerationMapper generationMapper;
    private final TeachingPlanFeedbackMapper feedbackMapper;
    private final TeachingActivityPlanService planService;
    private final ObjectMapper objectMapper;

    public TeachingPlanFeedbackServiceImpl(AiTeachingPlanGenerationMapper generationMapper,
                                           TeachingPlanFeedbackMapper feedbackMapper,
                                           TeachingActivityPlanService planService,
                                           ObjectMapper objectMapper) {
        this.generationMapper = generationMapper;
        this.feedbackMapper = feedbackMapper;
        this.planService = planService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Long recordGeneration(TeachingPlanGenerateRequest request,
                                 GeneratedTeachingPlanResponse response,
                                 Long accountId,
                                 String actorRole) {
        if (request == null || response == null || accountId == null || !StringUtils.hasText(actorRole)) {
            throw new IllegalArgumentException("generation actor and result are required");
        }
        AiTeachingPlanGeneration generation = new AiTeachingPlanGeneration();
        generation.setSchoolId(request.getSchoolId());
        generation.setAccountId(accountId);
        generation.setActorRole(actorRole.trim());
        generation.setThreadId(clean(response.getThreadId()));
        generation.setGrade(clean(response.getGrade()));
        generation.setTheme(clean(response.getTheme()));
        generation.setActivityType(clean(response.getActivityType()));
        generation.setDurationMinutes(response.getDurationMinutes());
        generation.setPracticeRequired(response.getPracticeRequired());
        generation.setGenerationStatus(clean(response.getGenerationStatus()));
        generation.setRetrievalStatus(clean(response.getRetrievalStatus()));
        generation.setLlmProvider(clean(response.getLlmProvider()));
        generation.setLlmModel(clean(response.getLlmModel()));
        generation.setPromptVersion(clean(response.getPromptVersion()));
        generation.setPromptRunId(clean(response.getPromptRunId()));
        generation.setPromptExperiment(clean(response.getPromptExperiment()));
        generation.setPromptVariant(clean(response.getPromptVariant()));
        generation.setRequestJson(writeJson(safeRequestSnapshot(request)));
        generation.setResponseJson(writeJson(safeResponseSnapshot(response)));
        generationMapper.insert(generation);
        return generation.getGenerationId();
    }

    @Override
    public PageResult<TeachingPlanGenerationVO> mine(AuthCurrentUserVO user,
                                                     String feedbackStatus,
                                                     Long pageNum,
                                                     Long pageSize) {
        requireTeacher(user);
        String safeFeedbackStatus = normalizeFeedbackStatus(feedbackStatus);
        long safePageNum = safePageNum(pageNum);
        long safePageSize = safePageSize(pageSize);
        LambdaQueryWrapper<AiTeachingPlanGeneration> query = new LambdaQueryWrapper<AiTeachingPlanGeneration>()
                .eq(AiTeachingPlanGeneration::getAccountId, user.getAccountId())
                .eq(AiTeachingPlanGeneration::getActorRole, "teacher");
        if ("submitted".equals(safeFeedbackStatus)) {
            query.inSql(AiTeachingPlanGeneration::getGenerationId,
                    "SELECT generation_id FROM teaching_plan_feedback");
        } else if ("pending".equals(safeFeedbackStatus)) {
            query.notInSql(AiTeachingPlanGeneration::getGenerationId,
                    "SELECT generation_id FROM teaching_plan_feedback");
        }
        query.orderByDesc(AiTeachingPlanGeneration::getCreatedAt)
                .orderByDesc(AiTeachingPlanGeneration::getGenerationId);
        Page<AiTeachingPlanGeneration> page = generationMapper.selectPage(
                new Page<>(safePageNum, safePageSize),
                query
        );
        List<Long> generationIds = page.getRecords().stream()
                .map(AiTeachingPlanGeneration::getGenerationId)
                .toList();
        Map<Long, TeachingPlanFeedback> feedbackByGeneration = generationIds.isEmpty()
                ? Map.of()
                : feedbackMapper.selectList(new LambdaQueryWrapper<TeachingPlanFeedback>()
                        .in(TeachingPlanFeedback::getGenerationId, generationIds)).stream()
                        .collect(Collectors.toMap(TeachingPlanFeedback::getGenerationId, Function.identity()));
        return PageResult.of(page.getRecords().stream()
                        .map(generation -> toGenerationVO(generation, feedbackByGeneration.get(generation.getGenerationId())))
                        .toList(),
                page.getTotal(), safePageNum, safePageSize);
    }

    @Override
    @Transactional
    public TeachingPlanFeedbackVO submitFeedback(Long generationId,
                                                 TeachingPlanFeedbackRequest request,
                                                 AuthCurrentUserVO user) {
        requireTeacher(user);
        validateFeedback(request);
        List<String> reasonCodes = normalizeReasonCodes(request.getReasonCodes());
        validateReasonRules(request, reasonCodes);
        String teacherNote = cleanNote(request.getTeacherNote());
        AiTeachingPlanGeneration generation = requireOwnedGenerationForUpdate(generationId, user.getAccountId(), true);
        TeachingPlanFeedback feedback = feedbackMapper.selectByGenerationIdForUpdate(generationId);
        if (feedback != null) {
            if (sameFeedback(feedback, request, reasonCodes, teacherNote)) {
                return toFeedbackVO(feedback, generation.getSavedPlanId());
            }
            throw conflict("feedback_already_submitted", "反馈已提交，不能再次修改");
        }
        if (Boolean.TRUE.equals(request.getAdopted()) && generation.getSavedPlanId() == null) {
            ensureSavedPlan(generation, buildCreateRequestFromSnapshot(generation));
        }

        LocalDateTime now = LocalDateTime.now();
        feedback = new TeachingPlanFeedback();
        feedback.setGenerationId(generationId);
        feedback.setTeacherAccountId(user.getAccountId());
        feedback.setAdopted(request.getAdopted());
        feedback.setRating(request.getRating());
        feedback.setReasonCodesJson(writeJson(reasonCodes));
        feedback.setTeacherNote(teacherNote);
        feedback.setSubmittedAt(now);
        feedbackMapper.insert(feedback);
        return toFeedbackVO(feedback, generation.getSavedPlanId());
    }

    @Override
    @Transactional
    public TeachingActivityPlanAdminVO saveDraftForGeneration(Long generationId,
                                                               Long accountId,
                                                               TeachingActivityPlanCreateRequest createRequest) {
        AiTeachingPlanGeneration generation = requireOwnedGenerationForUpdate(generationId, accountId, false);
        if (generation.getSavedPlanId() != null) {
            TeachingActivityPlanAdminVO existing = planService.getPlanAdminDetail(generation.getSavedPlanId());
            if (existing == null) {
                throw conflict("saved_plan_missing", "生成记录关联的方案草稿不存在");
            }
            return existing;
        }
        if (createRequest == null) {
            createRequest = buildCreateRequestFromSnapshot(generation);
        }
        createRequest.setPlanCode(planCode(generationId));
        createRequest.setSchoolId(generation.getSchoolId());
        return ensureSavedPlan(generation, createRequest);
    }

    @Override
    public TeachingPlanFeedbackReportVO report(Long schoolId,
                                               LocalDate startDate,
                                               LocalDate endDate,
                                               String theme,
                                               String feedbackStatus,
                                               Boolean adopted,
                                               Boolean lowScoreOnly,
                                               String reasonCode,
                                               Long pageNum,
                                               Long pageSize) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw badRequest("invalid_date_range", "endDate cannot be before startDate");
        }
        String safeFeedbackStatus = normalizeFeedbackStatus(feedbackStatus);
        String safeReasonCode = normalizeReasonCodeFilter(reasonCode);
        LocalDateTime startAt = startDate == null ? null : startDate.atStartOfDay();
        LocalDateTime endExclusive = endDate == null ? null : endDate.plusDays(1).atStartOfDay();
        String safeTheme = clean(theme);
        boolean onlyLowScore = Boolean.TRUE.equals(lowScoreOnly);
        long safePageNum = safePageNum(pageNum);
        long safePageSize = safePageSize(pageSize);
        long offset = (safePageNum - 1L) * safePageSize;

        TeachingPlanFeedbackReportSummaryVO summary = generationMapper.selectReportSummary(
                schoolId, startAt, endExclusive, safeTheme, safeFeedbackStatus, adopted, onlyLowScore,
                safeReasonCode);
        if (summary == null) {
            summary = new TeachingPlanFeedbackReportSummaryVO();
        }
        long total = generationMapper.countReportRows(
                schoolId, startAt, endExclusive, safeTheme, safeFeedbackStatus, adopted, onlyLowScore,
                safeReasonCode);
        List<TeachingPlanFeedbackReportItemVO> rows = generationMapper.selectReportRows(
                schoolId, startAt, endExclusive, safeTheme, safeFeedbackStatus, adopted, onlyLowScore,
                safeReasonCode,
                offset, safePageSize);
        rows.forEach(row -> {
            row.setPlan(readJsonMap(row.getResponseJson()));
            row.setReasonCodes(readReasonCodes(row.getReasonCodesJson()));
        });

        TeachingPlanFeedbackReportVO result = new TeachingPlanFeedbackReportVO();
        result.setGenerationCount(summary.getGenerationCount());
        result.setFeedbackCount(summary.getFeedbackCount());
        result.setFeedbackRate(percent(summary.getFeedbackCount(), summary.getGenerationCount()));
        result.setAdoptedCount(summary.getAdoptedCount());
        result.setNotAdoptedCount(summary.getNotAdoptedCount());
        result.setAdoptionRate(percent(summary.getAdoptedCount(), summary.getFeedbackCount()));
        result.setAverageRating(summary.getAverageRating() == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : summary.getAverageRating().setScale(2, RoundingMode.HALF_UP));
        Map<Integer, Long> distribution = new LinkedHashMap<>();
        distribution.put(1, summary.getRatingOneCount());
        distribution.put(2, summary.getRatingTwoCount());
        distribution.put(3, summary.getRatingThreeCount());
        distribution.put(4, summary.getRatingFourCount());
        distribution.put(5, summary.getRatingFiveCount());
        result.setRatingDistribution(distribution);
        Map<String, Long> reasonDistribution = new LinkedHashMap<>();
        TeachingPlanFeedbackReason.orderedCodes().forEach(code -> reasonDistribution.put(code, 0L));
        reasonDistribution.put("UNSPECIFIED", 0L);
        List<TeachingPlanFeedbackReasonCountVO> reasonCounts = generationMapper.selectReasonCounts(
                schoolId, startAt, endExclusive, safeTheme, safeFeedbackStatus, adopted, onlyLowScore,
                safeReasonCode);
        if (reasonCounts != null) {
            reasonCounts.forEach(count -> {
                if (TeachingPlanFeedbackReason.supports(count.getReasonCode())) {
                    reasonDistribution.put(count.getReasonCode(), count.getReasonCount());
                }
            });
        }
        reasonDistribution.put("UNSPECIFIED", generationMapper.countUnspecifiedReasons(
                schoolId, startAt, endExclusive, safeTheme, safeFeedbackStatus, adopted, onlyLowScore,
                safeReasonCode));
        result.setReasonDistribution(reasonDistribution);
        result.setDetails(PageResult.of(rows, total, safePageNum, safePageSize));
        return result;
    }

    private TeachingActivityPlanAdminVO ensureSavedPlan(AiTeachingPlanGeneration generation,
                                                         TeachingActivityPlanCreateRequest createRequest) {
        createRequest.setPlanCode(planCode(generation.getGenerationId()));
        createRequest.setSchoolId(generation.getSchoolId());
        TeachingActivityPlanAdminVO plan = planService.createPlan(createRequest);
        generation.setSavedPlanId(plan.getPlanId());
        generationMapper.updateById(generation);
        return plan;
    }

    private TeachingActivityPlanCreateRequest buildCreateRequestFromSnapshot(AiTeachingPlanGeneration generation) {
        GeneratedTeachingPlanResponse response;
        try {
            response = objectMapper.readValue(generation.getResponseJson(), GeneratedTeachingPlanResponse.class);
        } catch (JsonProcessingException exception) {
            throw conflict("generation_snapshot_invalid", "教学方案快照无法读取");
        }
        TeachingActivityPlanCreateRequest request = new TeachingActivityPlanCreateRequest();
        request.setPlanCode(planCode(generation.getGenerationId()));
        request.setSchoolId(generation.getSchoolId());
        request.setTheme(defaultText(response.getTheme(), generation.getTheme()));
        ActivityType activityType = ActivityType.fromValue(defaultText(response.getActivityType(), generation.getActivityType()));
        request.setActivityType(activityType == null ? ActivityType.CLASSROOM : activityType);
        request.setSuitableGrade(defaultText(response.getGrade(), generation.getGrade()));
        request.setObjectiveText(joinLines(response.getObjectives(), "教学目标待完善。"));
        request.setActivityContent(joinLines(response.getActivityFlow(), "活动流程待完善。"));
        request.setPreparationText(joinLines(response.getPreparation(), "课前准备待完善。"));
        request.setSafetyText(joinLines(response.getSafetyNotes(), "安全提示待完善。"));
        List<String> outcomes = new ArrayList<>();
        if (response.getReflection() != null) outcomes.addAll(response.getReflection());
        if (response.getEvaluation() != null) outcomes.addAll(response.getEvaluation());
        request.setExpectedOutcome(joinLines(outcomes, "形成学习记录、交流展示和反思评价。"));
        request.setDurationMinutes(response.getDurationMinutes());
        return request;
    }

    private AiTeachingPlanGeneration requireOwnedGenerationForUpdate(Long generationId,
                                                                      Long accountId,
                                                                      boolean teacherOnly) {
        if (generationId == null) {
            throw badRequest("generation_id_required", "generationId is required");
        }
        AiTeachingPlanGeneration generation = generationMapper.selectByIdForUpdate(generationId);
        if (generation == null) {
            throw new TeachingPlanFeedbackException(HttpStatus.NOT_FOUND,
                    "generation_not_found", "教学方案生成记录不存在");
        }
        if (accountId == null || !accountId.equals(generation.getAccountId())) {
            throw forbidden("generation_owner_required", "只能操作本人生成的教学方案");
        }
        if (teacherOnly && !"teacher".equals(generation.getActorRole())) {
            throw forbidden("teacher_generation_required", "只有教师生成记录可以提交反馈");
        }
        return generation;
    }

    private void requireTeacher(AuthCurrentUserVO user) {
        if (user == null || !"teacher".equals(user.getRoleCode()) || user.getAccountId() == null) {
            throw forbidden("teacher_access_required", "teacher access required");
        }
    }

    private void validateFeedback(TeachingPlanFeedbackRequest request) {
        if (request == null || request.getAdopted() == null) {
            throw badRequest("adopted_required", "adopted is required");
        }
        if (request.getRating() == null || request.getRating() < 1 || request.getRating() > 5) {
            throw badRequest("rating_out_of_range", "rating must be between 1 and 5");
        }
        if (request.getTeacherNote() != null && request.getTeacherNote().length() > MAX_NOTE_LENGTH) {
            throw badRequest("teacher_note_too_long", "teacherNote must not exceed 2000 characters");
        }
    }

    private List<String> normalizeReasonCodes(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> requested = new LinkedHashSet<>();
        for (String value : values) {
            String code = clean(value);
            if (code == null || !TeachingPlanFeedbackReason.supports(code)) {
                throw badRequest("invalid_reason_code", "reasonCodes contains an unsupported value");
            }
            requested.add(code);
        }
        return TeachingPlanFeedbackReason.orderedCodes().stream().filter(requested::contains).toList();
    }

    private void validateReasonRules(TeachingPlanFeedbackRequest request, List<String> reasonCodes) {
        boolean positive = Boolean.TRUE.equals(request.getAdopted()) && request.getRating() >= 3;
        if (positive && !reasonCodes.isEmpty()) {
            throw badRequest("reason_codes_not_applicable", "positive feedback cannot include reasonCodes");
        }
        if (reasonCodes.contains(TeachingPlanFeedbackReason.OTHER.name())
                && !StringUtils.hasText(request.getTeacherNote())) {
            throw badRequest("other_reason_note_required", "teacherNote is required when OTHER is selected");
        }
    }

    private boolean sameFeedback(TeachingPlanFeedback existing,
                                 TeachingPlanFeedbackRequest request,
                                 List<String> reasonCodes,
                                 String teacherNote) {
        return Objects.equals(existing.getAdopted(), request.getAdopted())
                && Objects.equals(existing.getRating(), request.getRating())
                && Objects.equals(cleanNote(existing.getTeacherNote()), teacherNote)
                && Objects.equals(readReasonCodes(existing.getReasonCodesJson()), reasonCodes);
    }

    private String normalizeFeedbackStatus(String value) {
        if (!StringUtils.hasText(value) || "all".equalsIgnoreCase(value)) return null;
        String normalized = value.trim().toLowerCase();
        if (!"submitted".equals(normalized) && !"pending".equals(normalized)) {
            throw badRequest("invalid_feedback_status", "feedbackStatus must be submitted or pending");
        }
        return normalized;
    }

    private String normalizeReasonCodeFilter(String value) {
        String normalized = clean(value);
        if (normalized == null || "all".equalsIgnoreCase(normalized)) return null;
        normalized = normalized.toUpperCase();
        if (!"UNSPECIFIED".equals(normalized) && !TeachingPlanFeedbackReason.supports(normalized)) {
            throw badRequest("invalid_reason_code", "reasonCode is unsupported");
        }
        return normalized;
    }

    private TeachingPlanGenerationVO toGenerationVO(AiTeachingPlanGeneration generation,
                                                     TeachingPlanFeedback feedback) {
        TeachingPlanGenerationVO vo = new TeachingPlanGenerationVO();
        vo.setGenerationId(generation.getGenerationId());
        vo.setSchoolId(generation.getSchoolId());
        vo.setThreadId(generation.getThreadId());
        vo.setTheme(generation.getTheme());
        vo.setGrade(generation.getGrade());
        vo.setActivityType(generation.getActivityType());
        vo.setDurationMinutes(generation.getDurationMinutes());
        vo.setPracticeRequired(generation.getPracticeRequired());
        vo.setGenerationStatus(generation.getGenerationStatus());
        vo.setRetrievalStatus(generation.getRetrievalStatus());
        vo.setLlmProvider(generation.getLlmProvider());
        vo.setLlmModel(generation.getLlmModel());
        vo.setSavedPlanId(generation.getSavedPlanId());
        vo.setPlan(readJsonMap(generation.getResponseJson()));
        vo.setFeedback(feedback == null ? null : toFeedbackVO(feedback, generation.getSavedPlanId()));
        vo.setCreatedAt(generation.getCreatedAt());
        return vo;
    }

    private TeachingPlanFeedbackVO toFeedbackVO(TeachingPlanFeedback feedback, Long savedPlanId) {
        TeachingPlanFeedbackVO vo = new TeachingPlanFeedbackVO();
        vo.setFeedbackId(feedback.getFeedbackId());
        vo.setGenerationId(feedback.getGenerationId());
        vo.setAdopted(feedback.getAdopted());
        vo.setRating(feedback.getRating());
        vo.setReasonCodes(readReasonCodes(feedback.getReasonCodesJson()));
        vo.setTeacherNote(feedback.getTeacherNote());
        vo.setSavedPlanId(savedPlanId);
        vo.setSubmittedAt(feedback.getSubmittedAt());
        return vo;
    }

    private Map<String, Object> safeRequestSnapshot(TeachingPlanGenerateRequest request) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("schoolId", request.getSchoolId());
        values.put("grade", request.getGrade());
        values.put("theme", request.getTheme());
        values.put("activityType", request.getActivityType() == null ? null : request.getActivityType().getValue());
        values.put("durationMinutes", request.getDurationMinutes());
        values.put("practiceRequired", request.getPracticeRequired());
        values.put("modelId", clean(request.getModelId()));
        return values;
    }

    private Map<String, Object> safeResponseSnapshot(GeneratedTeachingPlanResponse response) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("generationStatus", response.getGenerationStatus());
        values.put("retrievalStatus", response.getRetrievalStatus());
        values.put("retrievalMethods", response.getRetrievalMethods());
        values.put("message", response.getMessage());
        values.put("theme", response.getTheme());
        values.put("grade", response.getGrade());
        values.put("activityType", response.getActivityType());
        values.put("durationMinutes", response.getDurationMinutes());
        values.put("practiceRequired", response.getPracticeRequired());
        values.put("objectives", response.getObjectives());
        values.put("resourceBasis", response.getResourceBasis());
        values.put("activityFlow", response.getActivityFlow());
        values.put("preparation", response.getPreparation());
        values.put("fieldTasks", response.getFieldTasks());
        values.put("safetyNotes", response.getSafetyNotes());
        values.put("reflection", response.getReflection());
        values.put("evaluation", response.getEvaluation());
        values.put("citations", response.getCitations());
        values.put("relatedResources", response.getRelatedResources());
        values.put("followUpSuggestions", response.getFollowUpSuggestions());
        return values;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("teaching plan snapshot serialization failed", exception);
        }
    }

    private Map<String, Object> readJsonMap(String json) {
        if (!StringUtils.hasText(json)) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private List<String> readReasonCodes(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<>() { });
            return normalizeStoredReasonCodes(values);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private List<String> normalizeStoredReasonCodes(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> supported = values.stream()
                .filter(TeachingPlanFeedbackReason::supports)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return TeachingPlanFeedbackReason.orderedCodes().stream().filter(supported::contains).toList();
    }

    private BigDecimal percent(long numerator, long denominator) {
        if (denominator <= 0) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private long safePageNum(Long value) {
        return value == null || value <= 0 ? DEFAULT_PAGE_NUM : value;
    }

    private long safePageSize(Long value) {
        return value == null || value <= 0 ? DEFAULT_PAGE_SIZE : Math.min(value, MAX_PAGE_SIZE);
    }

    private String planCode(Long generationId) {
        return "AI_GEN_" + generationId;
    }

    private String joinLines(List<String> values, String fallback) {
        if (values == null) return fallback;
        String joined = values.stream().filter(StringUtils::hasText).map(String::trim)
                .collect(Collectors.joining("\n"));
        return StringUtils.hasText(joined) ? joined : fallback;
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String cleanNote(String value) {
        return clean(value);
    }

    private TeachingPlanFeedbackException badRequest(String code, String message) {
        return new TeachingPlanFeedbackException(HttpStatus.BAD_REQUEST, code, message);
    }

    private TeachingPlanFeedbackException forbidden(String code, String message) {
        return new TeachingPlanFeedbackException(HttpStatus.FORBIDDEN, code, message);
    }

    private TeachingPlanFeedbackException conflict(String code, String message) {
        return new TeachingPlanFeedbackException(HttpStatus.CONFLICT, code, message);
    }
}
