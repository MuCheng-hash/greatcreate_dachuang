package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.entity.School;
import com.redculture.platform.entity.SchoolRegistration;
import com.redculture.platform.entity.SchoolUserAccount;
import com.redculture.platform.enums.AccountStatus;
import com.redculture.platform.enums.RegistrationReviewStatus;
import com.redculture.platform.mapper.SchoolRegistrationMapper;
import com.redculture.platform.service.SchoolRegistrationService;
import com.redculture.platform.service.SchoolService;
import com.redculture.platform.service.SchoolUserAccountService;
import com.redculture.platform.vo.SchoolRegistrationAdminVO;
import com.redculture.platform.vo.request.RegistrationReviewRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
public class SchoolRegistrationServiceImpl extends ServiceImpl<SchoolRegistrationMapper, SchoolRegistration>
        implements SchoolRegistrationService {

    private static final long DEFAULT_PAGE_NUM = 1L;
    private static final long DEFAULT_PAGE_SIZE = 10L;
    private static final long MAX_PAGE_SIZE = 100L;

    private final SchoolService schoolService;
    private final SchoolUserAccountService schoolUserAccountService;

    public SchoolRegistrationServiceImpl(SchoolService schoolService,
                                         SchoolUserAccountService schoolUserAccountService) {
        this.schoolService = schoolService;
        this.schoolUserAccountService = schoolUserAccountService;
    }

    @Override
    public PageResult<SchoolRegistrationAdminVO> pageRegistrations(String keyword,
                                                                   RegistrationReviewStatus reviewStatus,
                                                                   Long pageNum,
                                                                   Long pageSize) {
        long safePageNum = pageNum == null || pageNum <= 0 ? DEFAULT_PAGE_NUM : pageNum;
        long safePageSize = pageSize == null || pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        LambdaQueryWrapper<SchoolRegistration> wrapper = new LambdaQueryWrapper<SchoolRegistration>()
                .eq(reviewStatus != null, SchoolRegistration::getReviewStatus, reviewStatus)
                .orderByDesc(SchoolRegistration::getCreatedAt);
        if (StringUtils.hasText(keyword)) {
            String cleanKeyword = keyword.trim();
            wrapper.and(item -> item.like(SchoolRegistration::getSchoolName, cleanKeyword)
                    .or()
                    .like(SchoolRegistration::getApplyAccount, cleanKeyword)
                    .or()
                    .like(SchoolRegistration::getContactPhone, cleanKeyword));
        }
        Page<SchoolRegistration> page = page(new Page<>(safePageNum, safePageSize), wrapper);
        return PageResult.of(page.getRecords().stream().map(this::toAdminVO).toList(), page.getTotal(), safePageNum, safePageSize);
    }

    @Override
    public SchoolRegistrationAdminVO getRegistrationDetail(Long registrationId) {
        SchoolRegistration registration = getById(registrationId);
        return registration == null ? null : toAdminVO(registration);
    }

    @Override
    @Transactional(transactionManager = "mysqlTransactionManager")
    public SchoolRegistrationAdminVO approveRegistration(Long registrationId, RegistrationReviewRequest request) {
        SchoolRegistration registration = requireRegistration(registrationId);
        Long schoolId = request != null ? request.getLinkedSchoolId() : null;
        School linkedSchool = schoolId != null ? requireSchool(schoolId) : matchOrCreateSchool(registration);
        ensureSchoolHasNoAccount(linkedSchool.getSchoolId(), registrationId);
        upsertSchoolAccount(registration, linkedSchool);

        registration.setLinkedSchoolId(linkedSchool.getSchoolId());
        registration.setReviewStatus(RegistrationReviewStatus.APPROVED);
        registration.setReviewRemark(clean(request == null ? null : request.getReviewRemark()));
        registration.setReviewedBy(clean(request == null ? null : request.getReviewerName()));
        registration.setReviewedAt(LocalDateTime.now());
        updateById(registration);
        return toAdminVO(getById(registrationId));
    }

    @Override
    @Transactional(transactionManager = "mysqlTransactionManager")
    public SchoolRegistrationAdminVO rejectRegistration(Long registrationId, RegistrationReviewRequest request) {
        SchoolRegistration registration = requireRegistration(registrationId);
        registration.setReviewStatus(RegistrationReviewStatus.REJECTED);
        registration.setReviewRemark(clean(request == null ? null : request.getReviewRemark()));
        registration.setReviewedBy(clean(request == null ? null : request.getReviewerName()));
        registration.setReviewedAt(LocalDateTime.now());
        updateById(registration);
        return toAdminVO(getById(registrationId));
    }

    private SchoolRegistration requireRegistration(Long registrationId) {
        if (registrationId == null) {
            throw new IllegalArgumentException("注册申请编号不能为空");
        }
        SchoolRegistration registration = getById(registrationId);
        if (registration == null) {
            throw new IllegalArgumentException("注册申请不存在");
        }
        return registration;
    }

    private School requireSchool(Long schoolId) {
        School school = schoolService.getById(schoolId);
        if (school == null) {
            throw new IllegalArgumentException("关联学校不存在");
        }
        return school;
    }

    private School matchOrCreateSchool(SchoolRegistration registration) {
        School existing = schoolService.getOne(new LambdaQueryWrapper<School>()
                .eq(School::getSchoolName, registration.getSchoolName())
                .eq(registration.getCountyRegionId() != null, School::getCountyRegionId, registration.getCountyRegionId())
                .eq(registration.getTownshipRegionId() != null, School::getTownshipRegionId, registration.getTownshipRegionId())
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }

        School school = new School();
        school.setSchoolName(registration.getSchoolName());
        school.setCountyRegionId(registration.getCountyRegionId());
        school.setTownshipRegionId(registration.getTownshipRegionId());
        school.setSchoolType(registration.getSchoolType());
        school.setAddress(registration.getAddress());
        school.setContactPhone(registration.getContactPhone());
        school.setLongitude(registration.getLongitude());
        school.setLatitude(registration.getLatitude());
        school.setIntro(registration.getIntro());
        school.setActive(true);
        schoolService.save(school);
        return school;
    }

    private void ensureSchoolHasNoAccount(Long schoolId, Long registrationId) {
        SchoolUserAccount existing = schoolUserAccountService.getOne(new LambdaQueryWrapper<SchoolUserAccount>()
                .eq(SchoolUserAccount::getSchoolId, schoolId)
                .last("LIMIT 1"));
        if (existing != null && !java.util.Objects.equals(existing.getRegistrationId(), registrationId)) {
            throw new IllegalArgumentException("该学校已经存在账号");
        }
    }

    private void upsertSchoolAccount(SchoolRegistration registration, School school) {
        SchoolUserAccount account = schoolUserAccountService.getOne(new LambdaQueryWrapper<SchoolUserAccount>()
                .eq(SchoolUserAccount::getRegistrationId, registration.getRegistrationId())
                .last("LIMIT 1"));
        if (account == null) {
            account = new SchoolUserAccount();
            account.setUsername(registration.getApplyAccount());
            account.setPasswordHash(registration.getPasswordHash());
            account.setRoleCode("school_admin");
            account.setRegistrationId(registration.getRegistrationId());
        }
        account.setSchoolId(school.getSchoolId());
        account.setDisplayName(school.getSchoolName());
        account.setContactName(registration.getContactName());
        account.setContactPhone(registration.getContactPhone());
        account.setStatus(AccountStatus.ACTIVE);
        if (account.getAccountId() == null) {
            schoolUserAccountService.save(account);
        } else {
            schoolUserAccountService.updateById(account);
        }
    }

    private SchoolRegistrationAdminVO toAdminVO(SchoolRegistration registration) {
        SchoolRegistrationAdminVO vo = new SchoolRegistrationAdminVO();
        vo.setRegistrationId(registration.getRegistrationId());
        vo.setApplyAccount(registration.getApplyAccount());
        vo.setContactName(registration.getContactName());
        vo.setContactPhone(registration.getContactPhone());
        vo.setContactEmail(registration.getContactEmail());
        vo.setSchoolName(registration.getSchoolName());
        vo.setSchoolAlias(registration.getSchoolAlias());
        vo.setSchoolLevel(enumValue(registration.getSchoolLevel()));
        vo.setSchoolType(registration.getSchoolType());
        vo.setSchoolNature(enumValue(registration.getSchoolNature()));
        vo.setCountyRegionId(registration.getCountyRegionId());
        vo.setTownshipRegionId(registration.getTownshipRegionId());
        vo.setAddress(registration.getAddress());
        vo.setLongitude(registration.getLongitude());
        vo.setLatitude(registration.getLatitude());
        vo.setGeoSourceType(enumValue(registration.getGeoSourceType()));
        vo.setGeoConfidence(enumValue(registration.getGeoConfidence()));
        vo.setIntro(registration.getIntro());
        vo.setReviewStatus(enumValue(registration.getReviewStatus()));
        vo.setReviewRemark(registration.getReviewRemark());
        vo.setReviewedBy(registration.getReviewedBy());
        vo.setReviewedAt(registration.getReviewedAt());
        vo.setLinkedSchoolId(registration.getLinkedSchoolId());
        vo.setCreatedAt(registration.getCreatedAt());
        vo.setUpdatedAt(registration.getUpdatedAt());
        return vo;
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

    private String clean(String value) {
        return value == null ? null : value.trim();
    }
}
