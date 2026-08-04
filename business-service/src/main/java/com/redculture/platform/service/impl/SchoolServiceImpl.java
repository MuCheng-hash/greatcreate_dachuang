package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.entity.School;
import com.redculture.platform.mapper.SchoolMapper;
import com.redculture.platform.service.SchoolService;
import com.redculture.platform.vo.SchoolAdminVO;
import com.redculture.platform.vo.request.SchoolCreateRequest;
import com.redculture.platform.vo.request.SchoolUpdateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class SchoolServiceImpl extends ServiceImpl<SchoolMapper, School> implements SchoolService {

    private static final long DEFAULT_PAGE_NUM = 1L;
    private static final long DEFAULT_PAGE_SIZE = 10L;
    private static final long MAX_PAGE_SIZE = 100L;

    @Override
    @Transactional
    public SchoolAdminVO createSchool(SchoolCreateRequest request) {
        validateCreateRequest(request);

        School school = new School();
        fillSchoolForCreate(school, request);
        school.setActive(true);
        save(school);
        return toSchoolAdminVO(school);
    }

    @Override
    @Transactional
    public SchoolAdminVO updateSchool(Long schoolId, SchoolUpdateRequest request) {
        School school = requireSchool(schoolId);
        fillSchoolForUpdate(school, request);
        updateById(school);
        return toSchoolAdminVO(getById(schoolId));
    }

    @Override
    @Transactional
    public void deleteSchool(Long schoolId) {
        School school = requireSchool(schoolId);
        removeById(school.getSchoolId());
    }

    @Override
    public SchoolAdminVO getSchoolAdminDetail(Long schoolId) {
        School school = getById(schoolId);
        return school == null ? null : toSchoolAdminVO(school);
    }

    @Override
    public PageResult<SchoolAdminVO> pageSchools(String keyword,
                                                 Long provinceRegionId,
                                                 Long cityRegionId,
                                                 Long countyRegionId,
                                                 Long townshipRegionId,
                                                 Long pageNum,
                                                 Long pageSize) {
        long safePageNum = pageNum == null || pageNum <= 0 ? DEFAULT_PAGE_NUM : pageNum;
        long safePageSize = pageSize == null || pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

        LambdaQueryWrapper<School> wrapper = new LambdaQueryWrapper<School>()
                .eq(provinceRegionId != null, School::getProvinceRegionId, provinceRegionId)
                .eq(cityRegionId != null, School::getCityRegionId, cityRegionId)
                .eq(countyRegionId != null, School::getCountyRegionId, countyRegionId)
                .eq(townshipRegionId != null, School::getTownshipRegionId, townshipRegionId)
                .orderByDesc(School::getCreatedAt);

        if (StringUtils.hasText(keyword)) {
            String cleanKeyword = keyword.trim();
            wrapper.and(item -> item.like(School::getSchoolName, cleanKeyword)
                    .or()
                    .like(School::getSchoolType, cleanKeyword)
                    .or()
                    .like(School::getAddress, cleanKeyword));
        }

        Page<School> page = page(new Page<>(safePageNum, safePageSize), wrapper);
        return PageResult.of(
                page.getRecords().stream().map(this::toSchoolAdminVO).toList(),
                page.getTotal(),
                safePageNum,
                safePageSize
        );
    }

    private void validateCreateRequest(SchoolCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        if (!StringUtils.hasText(request.getSchoolName())) {
            throw new IllegalArgumentException("schoolName is required");
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
        school.setSchoolName(clean(request.getSchoolName()));
        school.setProvinceRegionId(request.getProvinceRegionId());
        school.setCityRegionId(request.getCityRegionId());
        school.setCountyRegionId(request.getCountyRegionId());
        school.setTownshipRegionId(request.getTownshipRegionId());
        school.setSchoolType(clean(request.getSchoolType()));
        school.setAddress(clean(request.getAddress()));
        school.setContactPhone(clean(request.getContactPhone()));
        school.setPrincipalName(clean(request.getPrincipalName()));
        school.setLongitude(request.getLongitude());
        school.setLatitude(request.getLatitude());
        school.setIntro(clean(request.getIntro()));
    }

    private void fillSchoolForUpdate(School school, SchoolUpdateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        school.setSchoolName(valueOrOriginal(request.getSchoolName(), school.getSchoolName()));
        school.setProvinceRegionId(valueOrOriginal(request.getProvinceRegionId(), school.getProvinceRegionId()));
        school.setCityRegionId(valueOrOriginal(request.getCityRegionId(), school.getCityRegionId()));
        school.setCountyRegionId(valueOrOriginal(request.getCountyRegionId(), school.getCountyRegionId()));
        school.setTownshipRegionId(valueOrOriginal(request.getTownshipRegionId(), school.getTownshipRegionId()));
        school.setSchoolType(valueOrOriginal(request.getSchoolType(), school.getSchoolType()));
        school.setAddress(valueOrOriginal(request.getAddress(), school.getAddress()));
        school.setContactPhone(valueOrOriginal(request.getContactPhone(), school.getContactPhone()));
        school.setPrincipalName(valueOrOriginal(request.getPrincipalName(), school.getPrincipalName()));
        school.setLongitude(valueOrOriginal(request.getLongitude(), school.getLongitude()));
        school.setLatitude(valueOrOriginal(request.getLatitude(), school.getLatitude()));
        school.setIntro(valueOrOriginal(request.getIntro(), school.getIntro()));
        school.setActive(valueOrOriginal(request.getActive(), school.getActive()));
    }

    private SchoolAdminVO toSchoolAdminVO(School school) {
        SchoolAdminVO vo = new SchoolAdminVO();
        vo.setSchoolId(school.getSchoolId());
        vo.setSchoolName(school.getSchoolName());
        vo.setProvinceRegionId(school.getProvinceRegionId());
        vo.setCityRegionId(school.getCityRegionId());
        vo.setCountyRegionId(school.getCountyRegionId());
        vo.setTownshipRegionId(school.getTownshipRegionId());
        vo.setSchoolType(school.getSchoolType());
        vo.setAddress(school.getAddress());
        vo.setContactPhone(school.getContactPhone());
        vo.setPrincipalName(school.getPrincipalName());
        vo.setLongitude(school.getLongitude());
        vo.setLatitude(school.getLatitude());
        vo.setIntro(school.getIntro());
        vo.setActive(school.getActive());
        vo.setCreatedAt(school.getCreatedAt());
        vo.setUpdatedAt(school.getUpdatedAt());
        return vo;
    }

    private String clean(String value) {
        return value == null ? null : value.trim();
    }

    private <T> T valueOrOriginal(T newValue, T originalValue) {
        return newValue == null ? originalValue : newValue;
    }

    private String valueOrOriginal(String newValue, String originalValue) {
        return newValue == null ? originalValue : clean(newValue);
    }
}
