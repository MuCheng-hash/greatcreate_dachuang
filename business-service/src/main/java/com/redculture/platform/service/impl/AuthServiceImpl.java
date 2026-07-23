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
import com.redculture.platform.service.auth.AuthCurrentUserFactory;
import com.redculture.platform.service.auth.AuthTokenService;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.SchoolRegistrationSubmitVO;
import com.redculture.platform.vo.request.AuthLoginRequest;
import com.redculture.platform.vo.request.AuthPasswordChangeRequest;
import com.redculture.platform.vo.request.AuthProfileUpdateRequest;
import com.redculture.platform.vo.request.SchoolRegisterRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Objects;

@Service
public class AuthServiceImpl implements AuthService {

    private final SchoolRegistrationMapper schoolRegistrationMapper;
    private final SchoolUserAccountService schoolUserAccountService;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenService authTokenService;
    private final AuthCurrentUserFactory userFactory;

    @org.springframework.beans.factory.annotation.Autowired
    public AuthServiceImpl(SchoolRegistrationMapper schoolRegistrationMapper,
                           SchoolUserAccountService schoolUserAccountService,
                           SchoolService schoolService,
                           PasswordEncoder passwordEncoder,
                           AuthTokenService authTokenService,
                           AuthCurrentUserFactory userFactory) {
        this.schoolRegistrationMapper = schoolRegistrationMapper;
        this.schoolUserAccountService = schoolUserAccountService;
        this.passwordEncoder = passwordEncoder;
        this.authTokenService = authTokenService;
        this.userFactory = userFactory;
    }

    public AuthServiceImpl(SchoolRegistrationMapper schoolRegistrationMapper,
                           SchoolUserAccountService schoolUserAccountService,
                           SchoolService schoolService,
                           PasswordEncoder passwordEncoder) {
        this(schoolRegistrationMapper, schoolUserAccountService, schoolService, passwordEncoder,
                null, new AuthCurrentUserFactory(schoolService));
    }

    @Override
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
    public AuthCurrentUserVO login(AuthLoginRequest request) {
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
        return buildCurrentUser(account);
    }

    @Override
    public AuthCurrentUserVO currentUser(Long accountId) {
        SchoolUserAccount account = findCurrentAccount(accountId);
        if (account == null) {
            return null;
        }
        return buildCurrentUser(account);
    }

    @Override
    public AuthCurrentUserVO updateProfile(AuthProfileUpdateRequest request, Long accountId) {
        SchoolUserAccount account = requireCurrentAccount(accountId);
        if (request == null) {
            throw new IllegalArgumentException("profile request is required");
        }
        account.setDisplayName(cleanWithLimit(request.getDisplayName(), 120, "displayName"));
        account.setContactName(cleanWithLimit(request.getContactName(), 100, "contactName"));
        account.setContactPhone(cleanWithLimit(request.getContactPhone(), 50, "contactPhone"));
        schoolUserAccountService.updateById(account);
        return buildCurrentUser(account);
    }

    @Override
    public void changePassword(AuthPasswordChangeRequest request, Long accountId) {
        SchoolUserAccount account = requireCurrentAccount(accountId);
        if (request == null || !StringUtils.hasText(request.getCurrentPassword())
                || !StringUtils.hasText(request.getNewPassword())) {
            throw new IllegalArgumentException("currentPassword and newPassword are required");
        }
        if (!passwordEncoder.matches(request.getCurrentPassword(), account.getPasswordHash())) {
            throw new IllegalArgumentException("current password is incorrect");
        }
        if (request.getNewPassword().length() < 6 || request.getNewPassword().length() > 128) {
            throw new IllegalArgumentException("new password must be between 6 and 128 characters");
        }
        account.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        schoolUserAccountService.updateById(account);
        if (authTokenService != null) {
            authTokenService.revokeAll(account.getAccountId(), "password_changed");
        }
    }

    private SchoolUserAccount findCurrentAccount(Long accountId) {
        if (accountId == null) {
            return null;
        }
        SchoolUserAccount account = schoolUserAccountService.getById(accountId);
        if (account == null || account.getStatus() != AccountStatus.ACTIVE) {
            return null;
        }
        return account;
    }

    private SchoolUserAccount requireCurrentAccount(Long accountId) {
        SchoolUserAccount account = findCurrentAccount(accountId);
        if (account == null) {
            throw new IllegalArgumentException("authentication required");
        }
        return account;
    }

    private AuthCurrentUserVO buildCurrentUser(SchoolUserAccount account) {
        return userFactory.build(account);
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
        if (request.getPassword().length() < 6 || request.getPassword().length() > 128) {
            throw new IllegalArgumentException("password must be between 6 and 128 characters");
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

    private String cleanWithLimit(String value, int maxLength, String fieldName) {
        String cleaned = clean(value);
        if (cleaned != null && cleaned.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " must not exceed " + maxLength + " characters");
        }
        return StringUtils.hasText(cleaned) ? cleaned : null;
    }
}
