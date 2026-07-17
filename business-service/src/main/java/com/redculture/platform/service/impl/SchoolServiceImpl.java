package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.redculture.platform.entity.School;
import com.redculture.platform.entity.SchoolGeoRecord;
import com.redculture.platform.enums.GeoConfidenceLevel;
import com.redculture.platform.enums.GeoReviewResult;
import com.redculture.platform.enums.GeoSourceType;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.mapper.SchoolMapper;
import com.redculture.platform.service.SchoolService;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.mapper.SchoolGeoRecordMapper;
import com.redculture.platform.vo.SchoolAdminVO;
import com.redculture.platform.vo.request.SchoolCreateRequest;
import com.redculture.platform.vo.request.SchoolReviewRequest;
import com.redculture.platform.vo.request.SchoolUpdateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class SchoolServiceImpl extends ServiceImpl<SchoolMapper, School> implements SchoolService {

    private static final long DEFAULT_PAGE_NUM = 1L;
    private static final long DEFAULT_PAGE_SIZE = 10L;
    private static final long MAX_PAGE_SIZE = 100L;

    private final SchoolGeoRecordMapper schoolGeoRecordMapper;

    public SchoolServiceImpl(SchoolGeoRecordMapper schoolGeoRecordMapper) {
        this.schoolGeoRecordMapper = schoolGeoRecordMapper;
    }

    @Override
    @Transactional
    public SchoolAdminVO createSchool(SchoolCreateRequest request) {
        validateCreateRequest(request);
        ensureSchoolCodeUnique(request.getSchoolCode(), null);

        School school = new School();
        fillSchoolForCreate(school, request);
        school.setReviewStatus(ReviewStatus.DRAFT);
        school.setActive(true);
        save(school);
        saveGeoRecordIfNeeded(school, "创建学校时记录坐标");
        return toSchoolAdminVO(school);
    }

    @Override
    @Transactional
    public SchoolAdminVO updateSchool(Long schoolId, SchoolUpdateRequest request) {
        School school = requireSchool(schoolId);
        fillSchoolForUpdate(school, request);
        updateById(school);
        saveGeoRecordIfNeeded(school, "更新学校时记录坐标");
        return toSchoolAdminVO(getById(schoolId));
    }

    @Override
    public SchoolAdminVO getSchoolAdminDetail(Long schoolId) {
        School school = getById(schoolId);
        return school == null ? null : toSchoolAdminVO(school);
    }

    @Override
    public PageResult<SchoolAdminVO> pageSchools(String keyword,
                                                 Long countyRegionId,
                                                 Long townshipRegionId,
                                                 String schoolLevel,
                                                 ReviewStatus reviewStatus,
                                                 Long pageNum,
                                                 Long pageSize) {
        long safePageNum = pageNum == null || pageNum <= 0 ? DEFAULT_PAGE_NUM : pageNum;
        long safePageSize = pageSize == null || pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

        LambdaQueryWrapper<School> wrapper = new LambdaQueryWrapper<School>()
                .eq(countyRegionId != null, School::getCountyRegionId, countyRegionId)
                .eq(townshipRegionId != null, School::getTownshipRegionId, townshipRegionId)
                .eq(reviewStatus != null, School::getReviewStatus, reviewStatus)
                .orderByDesc(School::getCreatedAt);

        if (StringUtils.hasText(keyword)) {
            String cleanKeyword = keyword.trim();
            wrapper.and(item -> item.like(School::getSchoolName, cleanKeyword)
                    .or()
                    .like(School::getSchoolCode, cleanKeyword)
                    .or()
                    .like(School::getSchoolAlias, cleanKeyword)
                    .or()
                    .like(School::getAddress, cleanKeyword));
        }
        if (StringUtils.hasText(schoolLevel)) {
            wrapper.apply("school_level = {0}", schoolLevel.trim());
        }

        Page<School> page = page(new Page<>(safePageNum, safePageSize), wrapper);
        return PageResult.of(
                page.getRecords().stream().map(this::toSchoolAdminVO).toList(),
                page.getTotal(),
                safePageNum,
                safePageSize
        );
    }

    @Override
    @Transactional
    public SchoolAdminVO submitReview(Long schoolId) {
        School school = requireSchool(schoolId);
        school.setReviewStatus(ReviewStatus.PENDING);
        updateById(school);
        return toSchoolAdminVO(getById(schoolId));
    }

    @Override
    @Transactional
    public SchoolAdminVO approve(Long schoolId, SchoolReviewRequest request) {
        School school = requireSchool(schoolId);
        school.setReviewStatus(ReviewStatus.APPROVED);
        updateById(school);
        return toSchoolAdminVO(getById(schoolId));
    }

    @Override
    @Transactional
    public SchoolAdminVO reject(Long schoolId, SchoolReviewRequest request) {
        School school = requireSchool(schoolId);
        school.setReviewStatus(ReviewStatus.REJECTED);
        updateById(school);
        return toSchoolAdminVO(getById(schoolId));
    }

    private void validateCreateRequest(SchoolCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        if (!StringUtils.hasText(request.getSchoolCode())) {
            throw new IllegalArgumentException("schoolCode is required");
        }
        if (!StringUtils.hasText(request.getSchoolName())) {
            throw new IllegalArgumentException("schoolName is required");
        }
    }

    private void ensureSchoolCodeUnique(String schoolCode, Long excludeSchoolId) {
        LambdaQueryWrapper<School> wrapper = new LambdaQueryWrapper<School>()
                .eq(School::getSchoolCode, schoolCode.trim());
        if (excludeSchoolId != null) {
            wrapper.ne(School::getSchoolId, excludeSchoolId);
        }
        if (count(wrapper) > 0) {
            throw new IllegalArgumentException("schoolCode already exists");
        }
    }

    private School requireSchool(Long schoolId) {
        if (schoolId == null) {
            throw new IllegalArgumentException("schoolId is required");
        }
        School school = getById(schoolId);
        if (school == null) {
            throw new IllegalArgumentException("school not found");
        }
        return school;
    }

    private void fillSchoolForCreate(School school, SchoolCreateRequest request) {
        school.setSchoolCode(request.getSchoolCode().trim());
        school.setSchoolName(clean(request.getSchoolName()));
        school.setSchoolAlias(clean(request.getSchoolAlias()));
        school.setRegionId(request.getRegionId());
        school.setCountyRegionId(request.getCountyRegionId());
        school.setTownshipRegionId(request.getTownshipRegionId());
        school.setVillageRegionId(request.getVillageRegionId());
        school.setSchoolLevel(request.getSchoolLevel());
        school.setSchoolType(clean(request.getSchoolType()));
        school.setSchoolNature(request.getSchoolNature());
        school.setRuralSchool(defaultBoolean(request.getRuralSchool(), true));
        school.setTeachingPoint(defaultBoolean(request.getTeachingPoint(), false));
        school.setAddress(clean(request.getAddress()));
        school.setPostcode(clean(request.getPostcode()));
        school.setContactPhone(clean(request.getContactPhone()));
        school.setPrincipalName(clean(request.getPrincipalName()));
        school.setLongitude(request.getLongitude());
        school.setLatitude(request.getLatitude());
        school.setGeoSourceType(defaultGeoSource(request.getGeoSourceType()));
        school.setPoiName(clean(request.getPoiName()));
        school.setPoiAddress(clean(request.getPoiAddress()));
        school.setPoiType(clean(request.getPoiType()));
        school.setGeoConfidence(defaultGeoConfidence(request.getGeoConfidence()));
        school.setGeoVerified(defaultBoolean(request.getGeoVerified(), false));
        school.setIntro(clean(request.getIntro()));
        school.setSourceId(request.getSourceId());
    }

    private void fillSchoolForUpdate(School school, SchoolUpdateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        school.setSchoolName(valueOrOriginal(request.getSchoolName(), school.getSchoolName()));
        school.setSchoolAlias(valueOrOriginal(request.getSchoolAlias(), school.getSchoolAlias()));
        school.setRegionId(valueOrOriginal(request.getRegionId(), school.getRegionId()));
        school.setCountyRegionId(valueOrOriginal(request.getCountyRegionId(), school.getCountyRegionId()));
        school.setTownshipRegionId(valueOrOriginal(request.getTownshipRegionId(), school.getTownshipRegionId()));
        school.setVillageRegionId(valueOrOriginal(request.getVillageRegionId(), school.getVillageRegionId()));
        school.setSchoolLevel(valueOrOriginal(request.getSchoolLevel(), school.getSchoolLevel()));
        school.setSchoolType(valueOrOriginal(request.getSchoolType(), school.getSchoolType()));
        school.setSchoolNature(valueOrOriginal(request.getSchoolNature(), school.getSchoolNature()));
        school.setRuralSchool(valueOrOriginal(request.getRuralSchool(), school.getRuralSchool()));
        school.setTeachingPoint(valueOrOriginal(request.getTeachingPoint(), school.getTeachingPoint()));
        school.setAddress(valueOrOriginal(request.getAddress(), school.getAddress()));
        school.setPostcode(valueOrOriginal(request.getPostcode(), school.getPostcode()));
        school.setContactPhone(valueOrOriginal(request.getContactPhone(), school.getContactPhone()));
        school.setPrincipalName(valueOrOriginal(request.getPrincipalName(), school.getPrincipalName()));
        school.setLongitude(valueOrOriginal(request.getLongitude(), school.getLongitude()));
        school.setLatitude(valueOrOriginal(request.getLatitude(), school.getLatitude()));
        school.setGeoSourceType(valueOrOriginal(request.getGeoSourceType(), school.getGeoSourceType()));
        school.setPoiName(valueOrOriginal(request.getPoiName(), school.getPoiName()));
        school.setPoiAddress(valueOrOriginal(request.getPoiAddress(), school.getPoiAddress()));
        school.setPoiType(valueOrOriginal(request.getPoiType(), school.getPoiType()));
        school.setGeoConfidence(valueOrOriginal(request.getGeoConfidence(), school.getGeoConfidence()));
        school.setGeoVerified(valueOrOriginal(request.getGeoVerified(), school.getGeoVerified()));
        school.setIntro(valueOrOriginal(request.getIntro(), school.getIntro()));
        school.setSourceId(valueOrOriginal(request.getSourceId(), school.getSourceId()));
        school.setActive(valueOrOriginal(request.getActive(), school.getActive()));
    }

    private void saveGeoRecordIfNeeded(School school, String remark) {
        if (school.getSchoolId() == null || school.getLongitude() == null || school.getLatitude() == null) {
            return;
        }

        schoolGeoRecordMapper.update(null, new LambdaUpdateWrapper<SchoolGeoRecord>()
                .eq(SchoolGeoRecord::getSchoolId, school.getSchoolId())
                .eq(SchoolGeoRecord::getCurrent, true)
                .set(SchoolGeoRecord::getCurrent, false));

        SchoolGeoRecord record = new SchoolGeoRecord();
        record.setSchoolId(school.getSchoolId());
        record.setLongitude(school.getLongitude());
        record.setLatitude(school.getLatitude());
        record.setSourceType(school.getGeoSourceType());
        record.setPoiName(school.getPoiName());
        record.setPoiAddress(school.getPoiAddress());
        record.setPoiType(school.getPoiType());
        record.setConfidenceLevel(school.getGeoConfidence());
        record.setManualReviewed(Boolean.TRUE.equals(school.getGeoVerified()));
        record.setReviewResult(Boolean.TRUE.equals(school.getGeoVerified()) ? GeoReviewResult.CONFIRMED : GeoReviewResult.PENDING);
        record.setCurrent(true);
        record.setRemark(remark);
        schoolGeoRecordMapper.insert(record);
    }

    private SchoolAdminVO toSchoolAdminVO(School school) {
        SchoolAdminVO vo = new SchoolAdminVO();
        vo.setSchoolId(school.getSchoolId());
        vo.setSchoolCode(school.getSchoolCode());
        vo.setSchoolName(school.getSchoolName());
        vo.setSchoolAlias(school.getSchoolAlias());
        vo.setRegionId(school.getRegionId());
        vo.setCountyRegionId(school.getCountyRegionId());
        vo.setTownshipRegionId(school.getTownshipRegionId());
        vo.setVillageRegionId(school.getVillageRegionId());
        vo.setSchoolLevel(enumValue(school.getSchoolLevel()));
        vo.setSchoolType(school.getSchoolType());
        vo.setSchoolNature(enumValue(school.getSchoolNature()));
        vo.setRuralSchool(school.getRuralSchool());
        vo.setTeachingPoint(school.getTeachingPoint());
        vo.setAddress(school.getAddress());
        vo.setPostcode(school.getPostcode());
        vo.setContactPhone(school.getContactPhone());
        vo.setPrincipalName(school.getPrincipalName());
        vo.setLongitude(school.getLongitude());
        vo.setLatitude(school.getLatitude());
        vo.setGeoSourceType(enumValue(school.getGeoSourceType()));
        vo.setPoiName(school.getPoiName());
        vo.setPoiAddress(school.getPoiAddress());
        vo.setPoiType(school.getPoiType());
        vo.setGeoConfidence(enumValue(school.getGeoConfidence()));
        vo.setGeoVerified(school.getGeoVerified());
        vo.setIntro(school.getIntro());
        vo.setSourceId(school.getSourceId());
        vo.setReviewStatus(enumValue(school.getReviewStatus()));
        vo.setActive(school.getActive());
        vo.setCreatedAt(school.getCreatedAt());
        vo.setUpdatedAt(school.getUpdatedAt());
        return vo;
    }

    private String clean(String value) {
        return value == null ? null : value.trim();
    }

    private Boolean defaultBoolean(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }

    private GeoSourceType defaultGeoSource(GeoSourceType value) {
        return value == null ? GeoSourceType.GOVERNMENT_DOC : value;
    }

    private GeoConfidenceLevel defaultGeoConfidence(GeoConfidenceLevel value) {
        return value == null ? GeoConfidenceLevel.UNKNOWN : value;
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
