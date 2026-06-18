package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.entity.LocalEduResource;
import com.redculture.platform.entity.School;
import com.redculture.platform.entity.SchoolResourceRel;
import com.redculture.platform.enums.ReachabilityLevel;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.enums.SchoolResourceRelationType;
import com.redculture.platform.enums.TravelMode;
import com.redculture.platform.mapper.SchoolResourceRelMapper;
import com.redculture.platform.service.LocalEduResourceService;
import com.redculture.platform.service.SchoolService;
import com.redculture.platform.service.SchoolResourceRelService;
import com.redculture.platform.vo.SchoolResourceRelAdminVO;
import com.redculture.platform.vo.request.SchoolResourceRelCreateRequest;
import com.redculture.platform.vo.request.SchoolResourceRelUpdateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SchoolResourceRelServiceImpl extends ServiceImpl<SchoolResourceRelMapper, SchoolResourceRel>
        implements SchoolResourceRelService {

    private static final long DEFAULT_PAGE_NUM = 1L;
    private static final long DEFAULT_PAGE_SIZE = 10L;
    private static final long MAX_PAGE_SIZE = 100L;

    private final SchoolService schoolService;
    private final LocalEduResourceService localEduResourceService;

    public SchoolResourceRelServiceImpl(SchoolService schoolService,
                                        LocalEduResourceService localEduResourceService) {
        this.schoolService = schoolService;
        this.localEduResourceService = localEduResourceService;
    }

    @Override
    @Transactional
    public SchoolResourceRelAdminVO createRelation(SchoolResourceRelCreateRequest request) {
        validateCreateRequest(request);
        ensureSchoolExists(request.getSchoolId());
        ensureResourceExists(request.getResourceId());
        ensureRelationUnique(request.getSchoolId(), request.getResourceId(), request.getRelationType(), null);

        SchoolResourceRel relation = new SchoolResourceRel();
        relation.setSchoolId(request.getSchoolId());
        relation.setResourceId(request.getResourceId());
        relation.setRelationType(defaultRelationType(request.getRelationType()));
        relation.setDistanceMeters(request.getDistanceMeters());
        relation.setRecommendedTravelMode(defaultTravelMode(request.getRecommendedTravelMode()));
        relation.setEstimatedDurationMinutes(request.getEstimatedDurationMinutes());
        relation.setReachabilityLevel(defaultReachability(request.getReachabilityLevel()));
        relation.setPriorityLevel(defaultPriority(request.getPriorityLevel()));
        relation.setEducationThemeSummary(clean(request.getEducationThemeSummary()));
        relation.setSourceId(request.getSourceId());
        relation.setReviewStatus(ReviewStatus.DRAFT);
        save(relation);
        return buildAdminVO(getById(relation.getRelId()));
    }

    @Override
    @Transactional
    public SchoolResourceRelAdminVO updateRelation(Long relId, SchoolResourceRelUpdateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        SchoolResourceRel relation = requireRelation(relId);
        SchoolResourceRelationType nextRelationType = valueOrOriginal(request.getRelationType(), relation.getRelationType());
        ensureRelationUnique(relation.getSchoolId(), relation.getResourceId(), nextRelationType, relId);

        relation.setRelationType(nextRelationType);
        relation.setDistanceMeters(valueOrOriginal(request.getDistanceMeters(), relation.getDistanceMeters()));
        relation.setRecommendedTravelMode(valueOrOriginal(request.getRecommendedTravelMode(), relation.getRecommendedTravelMode()));
        relation.setEstimatedDurationMinutes(valueOrOriginal(request.getEstimatedDurationMinutes(), relation.getEstimatedDurationMinutes()));
        relation.setReachabilityLevel(valueOrOriginal(request.getReachabilityLevel(), relation.getReachabilityLevel()));
        relation.setPriorityLevel(valueOrOriginal(request.getPriorityLevel(), relation.getPriorityLevel()));
        relation.setEducationThemeSummary(valueOrOriginal(request.getEducationThemeSummary(), relation.getEducationThemeSummary()));
        relation.setSourceId(valueOrOriginal(request.getSourceId(), relation.getSourceId()));
        relation.setReviewStatus(valueOrOriginal(request.getReviewStatus(), relation.getReviewStatus()));
        updateById(relation);
        return buildAdminVO(getById(relId));
    }

    @Override
    @Transactional
    public boolean deleteRelation(Long relId) {
        requireRelation(relId);
        return removeById(relId);
    }

    @Override
    public PageResult<SchoolResourceRelAdminVO> listBySchoolId(Long schoolId, Long pageNum, Long pageSize) {
        if (schoolId == null) {
            throw new IllegalArgumentException("schoolId is required");
        }
        return pageRelations(new LambdaQueryWrapper<SchoolResourceRel>()
                .eq(SchoolResourceRel::getSchoolId, schoolId)
                .orderByAsc(SchoolResourceRel::getPriorityLevel)
                .orderByAsc(SchoolResourceRel::getDistanceMeters), pageNum, pageSize);
    }

    @Override
    public PageResult<SchoolResourceRelAdminVO> listByResourceId(Long resourceId, Long pageNum, Long pageSize) {
        if (resourceId == null) {
            throw new IllegalArgumentException("resourceId is required");
        }
        return pageRelations(new LambdaQueryWrapper<SchoolResourceRel>()
                .eq(SchoolResourceRel::getResourceId, resourceId)
                .orderByAsc(SchoolResourceRel::getPriorityLevel)
                .orderByAsc(SchoolResourceRel::getDistanceMeters), pageNum, pageSize);
    }

    private PageResult<SchoolResourceRelAdminVO> pageRelations(LambdaQueryWrapper<SchoolResourceRel> wrapper,
                                                               Long pageNum,
                                                               Long pageSize) {
        long safePageNum = pageNum == null || pageNum <= 0 ? DEFAULT_PAGE_NUM : pageNum;
        long safePageSize = pageSize == null || pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        Page<SchoolResourceRel> page = page(new Page<>(safePageNum, safePageSize), wrapper);
        return PageResult.of(
                page.getRecords().stream().map(this::buildAdminVO).toList(),
                page.getTotal(),
                safePageNum,
                safePageSize
        );
    }

    private void validateCreateRequest(SchoolResourceRelCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        if (request.getSchoolId() == null) {
            throw new IllegalArgumentException("schoolId is required");
        }
        if (request.getResourceId() == null) {
            throw new IllegalArgumentException("resourceId is required");
        }
    }

    private void ensureSchoolExists(Long schoolId) {
        if (schoolService.getById(schoolId) == null) {
            throw new IllegalArgumentException("school not found");
        }
    }

    private void ensureResourceExists(Long resourceId) {
        if (localEduResourceService.getById(resourceId) == null) {
            throw new IllegalArgumentException("resource not found");
        }
    }

    private void ensureRelationUnique(Long schoolId,
                                      Long resourceId,
                                      SchoolResourceRelationType relationType,
                                      Long excludeRelId) {
        LambdaQueryWrapper<SchoolResourceRel> wrapper = new LambdaQueryWrapper<SchoolResourceRel>()
                .eq(SchoolResourceRel::getSchoolId, schoolId)
                .eq(SchoolResourceRel::getResourceId, resourceId)
                .eq(SchoolResourceRel::getRelationType, relationType);
        if (excludeRelId != null) {
            wrapper.ne(SchoolResourceRel::getRelId, excludeRelId);
        }
        if (count(wrapper) > 0) {
            throw new IllegalArgumentException("relation already exists");
        }
    }

    private SchoolResourceRel requireRelation(Long relId) {
        if (relId == null) {
            throw new IllegalArgumentException("relId is required");
        }
        SchoolResourceRel relation = getById(relId);
        if (relation == null) {
            throw new IllegalArgumentException("relation not found");
        }
        return relation;
    }

    private SchoolResourceRelAdminVO buildAdminVO(SchoolResourceRel relation) {
        School school = schoolService.getById(relation.getSchoolId());
        LocalEduResource resource = localEduResourceService.getById(relation.getResourceId());

        SchoolResourceRelAdminVO vo = new SchoolResourceRelAdminVO();
        vo.setRelId(relation.getRelId());
        vo.setSchoolId(relation.getSchoolId());
        vo.setSchoolName(school == null ? null : school.getSchoolName());
        vo.setResourceId(relation.getResourceId());
        vo.setResourceName(resource == null ? null : resource.getResourceName());
        vo.setRelationType(enumValue(relation.getRelationType()));
        vo.setDistanceMeters(relation.getDistanceMeters());
        vo.setRecommendedTravelMode(enumValue(relation.getRecommendedTravelMode()));
        vo.setEstimatedDurationMinutes(relation.getEstimatedDurationMinutes());
        vo.setReachabilityLevel(enumValue(relation.getReachabilityLevel()));
        vo.setPriorityLevel(relation.getPriorityLevel());
        vo.setEducationThemeSummary(relation.getEducationThemeSummary());
        vo.setSourceId(relation.getSourceId());
        vo.setReviewStatus(enumValue(relation.getReviewStatus()));
        vo.setCreatedAt(relation.getCreatedAt());
        vo.setUpdatedAt(relation.getUpdatedAt());
        return vo;
    }

    private String clean(String value) {
        return value == null ? null : value.trim();
    }

    private SchoolResourceRelationType defaultRelationType(SchoolResourceRelationType value) {
        return value == null ? SchoolResourceRelationType.NEARBY : value;
    }

    private TravelMode defaultTravelMode(TravelMode value) {
        return value == null ? TravelMode.UNKNOWN : value;
    }

    private ReachabilityLevel defaultReachability(ReachabilityLevel value) {
        return value == null ? ReachabilityLevel.UNKNOWN : value;
    }

    private Integer defaultPriority(Integer value) {
        return value == null ? 3 : value;
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
