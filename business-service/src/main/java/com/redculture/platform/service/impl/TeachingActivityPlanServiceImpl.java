package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.entity.LocalEduResource;
import com.redculture.platform.entity.School;
import com.redculture.platform.entity.TeachingActivityPlan;
import com.redculture.platform.enums.ActivityType;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.mapper.TeachingActivityPlanMapper;
import com.redculture.platform.service.LocalEduResourceService;
import com.redculture.platform.service.SchoolService;
import com.redculture.platform.service.TeachingActivityPlanService;
import com.redculture.platform.vo.TeachingActivityPlanAdminVO;
import com.redculture.platform.vo.request.TeachingActivityPlanCreateRequest;
import com.redculture.platform.vo.request.TeachingActivityPlanUpdateRequest;
import com.redculture.platform.mapper.TeachingActivityPlanResourceMapper;
import com.redculture.platform.vo.AuthCurrentUserVO;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

@Service
public class TeachingActivityPlanServiceImpl extends ServiceImpl<TeachingActivityPlanMapper, TeachingActivityPlan>
        implements TeachingActivityPlanService {

    private static final long DEFAULT_PAGE_NUM = 1L;
    private static final long DEFAULT_PAGE_SIZE = 10L;
    private static final long MAX_PAGE_SIZE = 100L;

    private final SchoolService schoolService;
    private final LocalEduResourceService localEduResourceService;
    private final TeachingActivityPlanResourceMapper planResourceMapper;

    public TeachingActivityPlanServiceImpl(SchoolService schoolService,
                                           LocalEduResourceService localEduResourceService) {
        this(schoolService, localEduResourceService, null);
    }

    @Autowired
    public TeachingActivityPlanServiceImpl(SchoolService schoolService,
                                           LocalEduResourceService localEduResourceService,
                                           TeachingActivityPlanResourceMapper planResourceMapper) {
        this.schoolService = schoolService;
        this.localEduResourceService = localEduResourceService;
        this.planResourceMapper = planResourceMapper;
    }

    @Override
    public TeachingActivityPlanAdminVO createPlan(TeachingActivityPlanCreateRequest request) {
        validateCreateRequest(request);
        ensurePlanCodeUnique(request.getPlanCode(), null);
        ensureSchoolExists(request.getSchoolId());
        ensureResourceExistsIfNeeded(request.getResourceId());

        TeachingActivityPlan plan = new TeachingActivityPlan();
        plan.setPlanCode(request.getPlanCode().trim());
        plan.setSchoolId(request.getSchoolId());
        plan.setResourceId(request.getResourceId());
        plan.setOwnerAccountId(request.getOwnerAccountId());
        plan.setPlanPayload(request.getPlanPayload());
        plan.setGenerationSource(request.getGenerationSource());
        plan.setAiRunId(request.getAiRunId());
        plan.setPublishedStatus("draft");
        plan.setTheme(clean(request.getTheme()));
        plan.setActivityType(defaultActivityType(request.getActivityType()));
        plan.setSuitableGrade(clean(request.getSuitableGrade()));
        plan.setObjectiveText(clean(request.getObjectiveText()));
        plan.setActivityContent(clean(request.getActivityContent()));
        plan.setPreparationText(clean(request.getPreparationText()));
        plan.setSafetyText(clean(request.getSafetyText()));
        plan.setExpectedOutcome(clean(request.getExpectedOutcome()));
        plan.setDurationMinutes(request.getDurationMinutes());
        plan.setSourceId(request.getSourceId());
        plan.setReviewStatus(ReviewStatus.DRAFT);
        plan.setActive(true);
        save(plan);
        replaceResources(plan.getPlanId(), request.getResourceIds(), request.getSchoolId());
        return buildAdminVO(getById(plan.getPlanId()));
    }

    @Override
    public TeachingActivityPlanAdminVO updatePlan(Long planId, TeachingActivityPlanUpdateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        TeachingActivityPlan plan = requirePlan(planId);
        ensureResourceExistsIfNeeded(request.getResourceId());

        plan.setResourceId(valueOrOriginal(request.getResourceId(), plan.getResourceId()));
        plan.setTheme(valueOrOriginal(request.getTheme(), plan.getTheme()));
        plan.setActivityType(valueOrOriginal(request.getActivityType(), plan.getActivityType()));
        plan.setSuitableGrade(valueOrOriginal(request.getSuitableGrade(), plan.getSuitableGrade()));
        plan.setObjectiveText(valueOrOriginal(request.getObjectiveText(), plan.getObjectiveText()));
        plan.setActivityContent(valueOrOriginal(request.getActivityContent(), plan.getActivityContent()));
        plan.setPreparationText(valueOrOriginal(request.getPreparationText(), plan.getPreparationText()));
        plan.setSafetyText(valueOrOriginal(request.getSafetyText(), plan.getSafetyText()));
        plan.setExpectedOutcome(valueOrOriginal(request.getExpectedOutcome(), plan.getExpectedOutcome()));
        plan.setDurationMinutes(valueOrOriginal(request.getDurationMinutes(), plan.getDurationMinutes()));
        plan.setPlanPayload(valueOrOriginal(request.getPlanPayload(), plan.getPlanPayload()));
        plan.setSourceId(valueOrOriginal(request.getSourceId(), plan.getSourceId()));
        plan.setReviewStatus(valueOrOriginal(request.getReviewStatus(), plan.getReviewStatus()));
        plan.setActive(valueOrOriginal(request.getActive(), plan.getActive()));
        updateById(plan);
        if (request.getResourceIds() != null) {
            replaceResources(plan.getPlanId(), request.getResourceIds(), plan.getSchoolId());
        }
        return buildAdminVO(getById(planId));
    }

    @Override
    public TeachingActivityPlanAdminVO getPlanAdminDetail(Long planId) {
        TeachingActivityPlan plan = getById(planId);
        return plan == null ? null : buildAdminVO(plan);
    }

    @Override
    public PageResult<TeachingActivityPlanAdminVO> pagePlans(Long schoolId,
                                                             Long resourceId,
                                                             String theme,
                                                             String activityType,
                                                             ReviewStatus reviewStatus,
                                                             Long pageNum,
                                                             Long pageSize) {
        long safePageNum = pageNum == null || pageNum <= 0 ? DEFAULT_PAGE_NUM : pageNum;
        long safePageSize = pageSize == null || pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

        LambdaQueryWrapper<TeachingActivityPlan> wrapper = new LambdaQueryWrapper<TeachingActivityPlan>()
                .eq(schoolId != null, TeachingActivityPlan::getSchoolId, schoolId)
                .eq(resourceId != null, TeachingActivityPlan::getResourceId, resourceId)
                .eq(reviewStatus != null, TeachingActivityPlan::getReviewStatus, reviewStatus)
                .orderByDesc(TeachingActivityPlan::getCreatedAt);

        if (StringUtils.hasText(theme)) {
            wrapper.like(TeachingActivityPlan::getTheme, theme.trim());
        }
        if (StringUtils.hasText(activityType)) {
            wrapper.apply("activity_type = {0}", activityType.trim());
        }

        Page<TeachingActivityPlan> page = page(new Page<>(safePageNum, safePageSize), wrapper);
        return PageResult.of(
                page.getRecords().stream().map(this::buildAdminVO).toList(),
                page.getTotal(),
                safePageNum,
                safePageSize
        );
    }

    @Override
    public PageResult<TeachingActivityPlanAdminVO> listBySchoolId(Long schoolId, Long pageNum, Long pageSize) {
        if (schoolId == null) {
            throw new IllegalArgumentException("schoolId is required");
        }
        return pagePlans(schoolId, null, null, null, null, pageNum, pageSize);
    }

    @Override
    public PageResult<TeachingActivityPlanAdminVO> listMine(Long accountId, Long schoolId, Long pageNum, Long pageSize) {
        if (accountId == null || schoolId == null) throw new IllegalArgumentException("authenticated school account is required");
        long safePageNum = pageNum == null || pageNum <= 0 ? DEFAULT_PAGE_NUM : pageNum;
        long safePageSize = pageSize == null || pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        LambdaQueryWrapper<TeachingActivityPlan> wrapper = new LambdaQueryWrapper<TeachingActivityPlan>()
                .eq(TeachingActivityPlan::getOwnerAccountId, accountId)
                .eq(TeachingActivityPlan::getSchoolId, schoolId)
                .orderByDesc(TeachingActivityPlan::getUpdatedAt);
        Page<TeachingActivityPlan> page = page(new Page<>(safePageNum, safePageSize), wrapper);
        return PageResult.of(page.getRecords().stream().map(this::buildAdminVO).toList(), page.getTotal(), safePageNum, safePageSize);
    }

    @Override
    public TeachingActivityPlanAdminVO getMine(Long planId, AuthCurrentUserVO user) {
        TeachingActivityPlan plan = requireOwned(planId, user);
        return buildAdminVO(plan);
    }

    @Override
    public TeachingActivityPlanAdminVO updateMine(Long planId, TeachingActivityPlanUpdateRequest request, AuthCurrentUserVO user) {
        TeachingActivityPlan plan = requireOwned(planId, user);
        TeachingActivityPlanAdminVO value = updatePlan(planId, request);
        if (plan.getReviewStatus() != ReviewStatus.DRAFT) {
            plan.setReviewStatus(ReviewStatus.DRAFT);
            plan.setPublishedStatus("draft");
            updateById(plan);
            value = buildAdminVO(plan);
        }
        return value;
    }

    @Override
    public TeachingActivityPlanAdminVO copyMine(Long planId, AuthCurrentUserVO user) {
        TeachingActivityPlan source = requireOwned(planId, user);
        TeachingActivityPlan copy = new TeachingActivityPlan();
        copy.setPlanCode("COPY_" + System.currentTimeMillis());
        copy.setSchoolId(source.getSchoolId());
        copy.setResourceId(source.getResourceId());
        copy.setOwnerAccountId(user.getAccountId());
        copy.setTheme(source.getTheme() + "（副本）");
        copy.setActivityType(source.getActivityType());
        copy.setSuitableGrade(source.getSuitableGrade());
        copy.setObjectiveText(source.getObjectiveText());
        copy.setActivityContent(source.getActivityContent());
        copy.setPreparationText(source.getPreparationText());
        copy.setSafetyText(source.getSafetyText());
        copy.setExpectedOutcome(source.getExpectedOutcome());
        copy.setDurationMinutes(source.getDurationMinutes());
        copy.setPlanPayload(source.getPlanPayload());
        copy.setGenerationSource(source.getGenerationSource());
        copy.setPublishedStatus("draft");
        copy.setReviewStatus(ReviewStatus.DRAFT);
        copy.setActive(true);
        save(copy);
        if (planResourceMapper != null) replaceResources(copy.getPlanId(), planResourceMapper.selectResourceIds(source.getPlanId()), copy.getSchoolId());
        return buildAdminVO(copy);
    }

    private TeachingActivityPlan requireOwned(Long planId, AuthCurrentUserVO user) {
        if (user == null || user.getAccountId() == null || user.getSchoolId() == null) throw new IllegalArgumentException("authentication required");
        TeachingActivityPlan plan = getById(planId);
        if (plan == null || !user.getAccountId().equals(plan.getOwnerAccountId()) || !user.getSchoolId().equals(plan.getSchoolId())) {
            throw new IllegalArgumentException("plan not found");
        }
        return plan;
    }

    private void replaceResources(Long planId, List<Long> resourceIds, Long schoolId) {
        if (planResourceMapper == null || planId == null) return;
        if (resourceIds == null) return;
        List<Long> ids = resourceIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        for (Long id : ids) ensureResourceExistsIfNeeded(id);
        planResourceMapper.deleteByPlanId(planId);
        for (int i = 0; i < ids.size(); i++) {
            Long id = ids.get(i);
            com.redculture.platform.entity.TeachingActivityPlanResource relation = new com.redculture.platform.entity.TeachingActivityPlanResource();
            relation.setPlanId(planId); relation.setResourceId(id); relation.setSortOrder(i); relation.setPrimary(i == 0);
            planResourceMapper.insert(relation);
        }
    }

    private void validateCreateRequest(TeachingActivityPlanCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        if (!StringUtils.hasText(request.getPlanCode())) {
            throw new IllegalArgumentException("planCode is required");
        }
        if (request.getSchoolId() == null) {
            throw new IllegalArgumentException("schoolId is required");
        }
        if (!StringUtils.hasText(request.getTheme())) {
            throw new IllegalArgumentException("theme is required");
        }
        if (!StringUtils.hasText(request.getActivityContent())) {
            throw new IllegalArgumentException("activityContent is required");
        }
    }

    private void ensurePlanCodeUnique(String planCode, Long excludePlanId) {
        LambdaQueryWrapper<TeachingActivityPlan> wrapper = new LambdaQueryWrapper<TeachingActivityPlan>()
                .eq(TeachingActivityPlan::getPlanCode, planCode.trim());
        if (excludePlanId != null) {
            wrapper.ne(TeachingActivityPlan::getPlanId, excludePlanId);
        }
        if (count(wrapper) > 0) {
            throw new IllegalArgumentException("planCode already exists");
        }
    }

    private void ensureSchoolExists(Long schoolId) {
        if (schoolService.getById(schoolId) == null) {
            throw new IllegalArgumentException("school not found");
        }
    }

    private void ensureResourceExistsIfNeeded(Long resourceId) {
        if (resourceId != null && localEduResourceService.getById(resourceId) == null) {
            throw new IllegalArgumentException("resource not found");
        }
    }

    private TeachingActivityPlan requirePlan(Long planId) {
        if (planId == null) {
            throw new IllegalArgumentException("planId is required");
        }
        TeachingActivityPlan plan = getById(planId);
        if (plan == null) {
            throw new IllegalArgumentException("plan not found");
        }
        return plan;
    }

    private TeachingActivityPlanAdminVO buildAdminVO(TeachingActivityPlan plan) {
        School school = schoolService.getById(plan.getSchoolId());
        LocalEduResource resource = plan.getResourceId() == null ? null : localEduResourceService.getById(plan.getResourceId());

        TeachingActivityPlanAdminVO vo = new TeachingActivityPlanAdminVO();
        vo.setPlanId(plan.getPlanId());
        vo.setPlanCode(plan.getPlanCode());
        vo.setSchoolId(plan.getSchoolId());
        vo.setSchoolName(school == null ? null : school.getSchoolName());
        vo.setResourceId(plan.getResourceId());
        vo.setOwnerAccountId(plan.getOwnerAccountId());
        vo.setPlanPayload(plan.getPlanPayload());
        vo.setGenerationSource(plan.getGenerationSource());
        vo.setAiRunId(plan.getAiRunId());
        vo.setPublishedStatus(plan.getPublishedStatus());
        if (planResourceMapper != null) vo.setResourceIds(planResourceMapper.selectResourceIds(plan.getPlanId()));
        vo.setResourceName(resource == null ? null : resource.getResourceName());
        vo.setTheme(plan.getTheme());
        vo.setActivityType(enumValue(plan.getActivityType()));
        vo.setSuitableGrade(plan.getSuitableGrade());
        vo.setObjectiveText(plan.getObjectiveText());
        vo.setActivityContent(plan.getActivityContent());
        vo.setPreparationText(plan.getPreparationText());
        vo.setSafetyText(plan.getSafetyText());
        vo.setExpectedOutcome(plan.getExpectedOutcome());
        vo.setDurationMinutes(plan.getDurationMinutes());
        vo.setSourceId(plan.getSourceId());
        vo.setReviewStatus(enumValue(plan.getReviewStatus()));
        vo.setActive(plan.getActive());
        vo.setCreatedAt(plan.getCreatedAt());
        vo.setUpdatedAt(plan.getUpdatedAt());
        return vo;
    }

    private String clean(String value) {
        return value == null ? null : value.trim();
    }

    private ActivityType defaultActivityType(ActivityType value) {
        return value == null ? ActivityType.CLASSROOM : value;
    }

    private <T> T valueOrOriginal(T newValue, T originalValue) {
        return newValue == null ? originalValue : newValue;
    }

    private String valueOrOriginal(String newValue, String originalValue) {
        return newValue == null ? originalValue : clean(newValue);
    }

    private String enumValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            Object enumValue = value.getClass().getMethod("getValue").invoke(value);
            return enumValue == null ? null : String.valueOf(enumValue);
        } catch (ReflectiveOperationException exception) {
            return String.valueOf(value);
        }
    }
}
