package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.entity.School;
import com.redculture.platform.entity.SchoolRegistration;
import com.redculture.platform.entity.SchoolUserAccount;
import com.redculture.platform.enums.AccountStatus;
import com.redculture.platform.enums.RegistrationReviewStatus;
import com.redculture.platform.mapper.SchoolRegistrationMapper;
import com.redculture.platform.service.AuthService;
import com.redculture.platform.service.SchoolService;
import com.redculture.platform.service.SchoolUserAccountService;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.SchoolRegistrationSubmitVO;
import com.redculture.platform.vo.request.AuthLoginRequest;
import com.redculture.platform.vo.request.SchoolRegisterRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Objects;

@Service
public class AuthServiceImpl implements AuthService {

    public static final String AUTH_SESSION_KEY = "CURRENT_SCHOOL_ACCOUNT_ID";

    private final SchoolRegistrationMapper schoolRegistrationMapper;
    private final SchoolUserAccountService schoolUserAccountService;
    private final SchoolService schoolService;
    private final PasswordEncoder passwordEncoder;

    public AuthServiceImpl(SchoolRegistrationMapper schoolRegistrationMapper,
                           SchoolUserAccountService schoolUserAccountService,
                           SchoolService schoolService,
                           PasswordEncoder passwordEncoder) {
        this.schoolRegistrationMapper = schoolRegistrationMapper;
        this.schoolUserAccountService = schoolUserAccountService;
        this.schoolService = schoolService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public SchoolRegistrationSubmitVO registerSchool(SchoolRegisterRequest request) {
        validateRegisterRequest(request);
        ensureUsernameAvailable(request.getUsername());

        SchoolRegistration registration = new SchoolRegistration();
        registration.setApplyAccount(request.getUsername().trim());
        registration.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        registration.setContactName(clean(request.getContactName()));
        registration.setContactPhone(clean(request.getContactPhone()));
        registration.setContactEmail(clean(request.getContactEmail()));
        registration.setSchoolName(clean(request.getSchoolName()));
        registration.setSchoolAlias(clean(request.getSchoolAlias()));
        registration.setSchoolLevel(request.getSchoolLevel());
        registration.setSchoolType(clean(request.getSchoolType()));
        registration.setSchoolNature(request.getSchoolNature());
        registration.setCountyRegionId(request.getCountyRegionId());
        registration.setTownshipRegionId(request.getTownshipRegionId());
        registration.setAddress(clean(request.getAddress()));
        registration.setLongitude(request.getLongitude());
        registration.setLatitude(request.getLatitude());
        registration.setGeoSourceType(request.getGeoSourceType());
        registration.setGeoConfidence(request.getGeoConfidence());
        registration.setIntro(clean(request.getIntro()));
        registration.setReviewStatus(RegistrationReviewStatus.PENDING);
        schoolRegistrationMapper.insert(registration);
        return new SchoolRegistrationSubmitVO(registration.getRegistrationId(), registration.getReviewStatus().getValue());
    }

    @Override
    @Transactional
    public AuthCurrentUserVO login(AuthLoginRequest request, HttpSession session) {
        if (request == null || !StringUtils.hasText(request.getUsername()) || !StringUtils.hasText(request.getPassword())) {
            throw new IllegalArgumentException("username and password are required");
        }

        SchoolUserAccount account = schoolUserAccountService.getOne(new LambdaQueryWrapper<SchoolUserAccount>()
                .eq(SchoolUserAccount::getUsername, request.getUsername().trim())
                .last("LIMIT 1"));
        if (account == null) {
            throw new IllegalArgumentException("account not found");
        }
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new IllegalArgumentException("account is not active");
        }
        if (!passwordEncoder.matches(request.getPassword(), account.getPasswordHash())) {
            throw new IllegalArgumentException("password is incorrect");
        }

        account.setLastLoginAt(java.time.LocalDateTime.now());
        schoolUserAccountService.updateById(account);
        session.setAttribute(AUTH_SESSION_KEY, account.getAccountId());
        return buildCurrentUser(account);
    }

    @Override
    public AuthCurrentUserVO currentUser(HttpSession session) {
        Object accountId = session.getAttribute(AUTH_SESSION_KEY);
        if (!(accountId instanceof Long id)) {
            return null;
        }
        SchoolUserAccount account = schoolUserAccountService.getById(id);
        if (account == null || account.getStatus() != AccountStatus.ACTIVE) {
            session.removeAttribute(AUTH_SESSION_KEY);
            return null;
        }
        return buildCurrentUser(account);
    }

    @Override
    public void logout(HttpSession session) {
        session.removeAttribute(AUTH_SESSION_KEY);
    }

    private AuthCurrentUserVO buildCurrentUser(SchoolUserAccount account) {
        School school = schoolService.getById(account.getSchoolId());
        AuthCurrentUserVO vo = new AuthCurrentUserVO();
        vo.setAccountId(account.getAccountId());
        vo.setUsername(account.getUsername());
        vo.setRoleCode(account.getRoleCode());
        vo.setSchoolId(account.getSchoolId());
        vo.setDisplayName(account.getDisplayName());
        if (school != null) {
            vo.setSchoolName(school.getSchoolName());
            vo.setSchoolLongitude(school.getLongitude());
            vo.setSchoolLatitude(school.getLatitude());
        }
        return vo;
    }

    private void validateRegisterRequest(SchoolRegisterRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        if (!StringUtils.hasText(request.getUsername())) {
            throw new IllegalArgumentException("username is required");
        }
        if (!StringUtils.hasText(request.getPassword())) {
            throw new IllegalArgumentException("password is required");
        }
        if (!StringUtils.hasText(request.getSchoolName())) {
            throw new IllegalArgumentException("schoolName is required");
        }
    }

    private void ensureUsernameAvailable(String username) {
        String cleanUsername = username.trim();
        if (schoolUserAccountService.count(new LambdaQueryWrapper<SchoolUserAccount>()
                .eq(SchoolUserAccount::getUsername, cleanUsername)) > 0) {
            throw new IllegalArgumentException("username already exists");
        }
        if (schoolRegistrationMapper.selectCount(new LambdaQueryWrapper<SchoolRegistration>()
                .eq(SchoolRegistration::getApplyAccount, cleanUsername)
                .in(SchoolRegistration::getReviewStatus, RegistrationReviewStatus.PENDING, RegistrationReviewStatus.APPROVED)) > 0) {
            throw new IllegalArgumentException("username already submitted");
        }
    }

    private String clean(String value) {
        return value == null ? null : value.trim();
    }
}
