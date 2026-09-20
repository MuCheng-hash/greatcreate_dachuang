package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.entity.School;
import com.redculture.platform.entity.SchoolUserAccount;
import com.redculture.platform.entity.StudentProfile;
import com.redculture.platform.entity.TeacherProfile;
import com.redculture.platform.entity.TeacherRegistrationInvite;
import com.redculture.platform.entity.UserProfile;
import com.redculture.platform.enums.AccountStatus;
import com.redculture.platform.mapper.StudentProfileMapper;
import com.redculture.platform.mapper.TeacherProfileMapper;
import com.redculture.platform.mapper.TeacherRegistrationInviteMapper;
import com.redculture.platform.mapper.UserProfileMapper;
import com.redculture.platform.service.AuthService;
import com.redculture.platform.service.SchoolService;
import com.redculture.platform.service.SchoolUserAccountService;
import com.redculture.platform.service.auth.AuthCurrentUserFactory;
import com.redculture.platform.service.auth.AuthTokenService;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.request.AuthLoginRequest;
import com.redculture.platform.vo.request.AuthPasswordChangeRequest;
import com.redculture.platform.vo.request.AuthProfileUpdateRequest;
import com.redculture.platform.vo.request.AccountRegisterRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class AuthServiceImpl implements AuthService {

    private final SchoolUserAccountService schoolUserAccountService;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenService authTokenService;
    private final AuthCurrentUserFactory userFactory;
    private final SchoolService schoolService;
    private final UserProfileMapper userProfileMapper;
    private final StudentProfileMapper studentProfileMapper;
    private final TeacherProfileMapper teacherProfileMapper;
    private final TeacherRegistrationInviteMapper teacherInviteMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public AuthServiceImpl(SchoolUserAccountService schoolUserAccountService,
                           SchoolService schoolService,
                           PasswordEncoder passwordEncoder,
                           AuthTokenService authTokenService,
                           AuthCurrentUserFactory userFactory,
                           UserProfileMapper userProfileMapper,
                           StudentProfileMapper studentProfileMapper,
                           TeacherProfileMapper teacherProfileMapper,
                           TeacherRegistrationInviteMapper teacherInviteMapper) {
        this.schoolUserAccountService = schoolUserAccountService;
        this.passwordEncoder = passwordEncoder;
        this.authTokenService = authTokenService;
        this.userFactory = userFactory;
        this.schoolService = schoolService;
        this.userProfileMapper = userProfileMapper;
        this.studentProfileMapper = studentProfileMapper;
        this.teacherProfileMapper = teacherProfileMapper;
        this.teacherInviteMapper = teacherInviteMapper;
    }

    @Override
    @Transactional
    public void registerAccount(AccountRegisterRequest request) {
        if (request == null) throw new IllegalArgumentException("registration 请求不能为空");
        String username = clean(request.getUsername());
        String realName = clean(request.getRealName());
        String roleCode = clean(request.getRoleCode());
        if (!StringUtils.hasText(username) || !StringUtils.hasText(request.getPassword()) || !StringUtils.hasText(realName)) {
            throw new IllegalArgumentException("用户名、密码和 realName 不能为空");
        }
        if (request.getPassword().length() < 6 || request.getPassword().length() > 128) {
            throw new IllegalArgumentException("密码长度必须在 6 到 128 个字符之间");
        }
        if (!"student".equals(roleCode) && !"teacher".equals(roleCode)) {
            throw new IllegalArgumentException("roleCode 必须为 student 或 teacher");
        }
        School school = request.getSchoolId() == null ? null : schoolService.getById(request.getSchoolId());
        if (school == null || !Boolean.TRUE.equals(school.getActive())) {
            throw new IllegalArgumentException("所选学校不可用");
        }
        ensureUsernameAvailable(username);

        if ("teacher".equals(roleCode)) {
            String code = clean(request.getTeacherInviteCode());
            if (!StringUtils.hasText(code)) throw new IllegalArgumentException("teacherInviteCode 不能为空");
            TeacherRegistrationInvite invite = teacherInviteMapper.selectOne(new LambdaQueryWrapper<TeacherRegistrationInvite>()
                    .eq(TeacherRegistrationInvite::getSchoolId, school.getSchoolId())
                    .eq(TeacherRegistrationInvite::getCodeHash, InviteCodeHasher.hash(code))
                    .last("LIMIT 1"));
            if (invite == null || teacherInviteMapper.consume(invite.getInviteId(), school.getSchoolId()) != 1) {
                throw new IllegalArgumentException("教师邀请码无效、已过期或已用尽");
            }
        }

        SchoolUserAccount account = new SchoolUserAccount();
        account.setUsername(username);
        account.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        account.setRoleCode(roleCode);
        account.setSchoolId(school.getSchoolId());
        account.setDisplayName(realName);
        account.setContactName(realName);
        account.setRealName(realName);
        account.setContactPhone(clean(request.getContactPhone()));
        account.setEmail(clean(request.getEmail()));
        account.setAccountType(roleCode);
        account.setStatus(AccountStatus.ACTIVE);
        account.setForcePasswordChange(false);
        account.setPasswordUpdatedAt(java.time.LocalDateTime.now());
        schoolUserAccountService.save(account);

        UserProfile profile = new UserProfile();
        profile.setAccountId(account.getAccountId());
        profile.setProfileType(roleCode);
        profile.setRealName(realName);
        profile.setPhone(account.getContactPhone());
        profile.setEmail(account.getEmail());
        profile.setSchoolId(school.getSchoolId());
        profile.setStatus("active");
        userProfileMapper.insert(profile);
        if ("teacher".equals(roleCode)) {
            TeacherProfile teacher = new TeacherProfile();
            teacher.setAccountId(account.getAccountId());
            teacher.setProfileId(profile.getProfileId());
            teacher.setSchoolId(school.getSchoolId());
            teacher.setTeacherName(realName);
            teacher.setStatus("active");
            teacherProfileMapper.insert(teacher);
        } else {
            StudentProfile student = new StudentProfile();
            student.setAccountId(account.getAccountId());
            student.setProfileId(profile.getProfileId());
            student.setSchoolId(school.getSchoolId());
            student.setStudentName(realName);
            student.setStatus("active");
            studentProfileMapper.insert(student);
        }
    }

    @Override
    public AuthCurrentUserVO login(AuthLoginRequest request) {
        if (request == null || !StringUtils.hasText(request.getUsername()) || !StringUtils.hasText(request.getPassword())) {
            throw new IllegalArgumentException("用户名和密码不能为空");
        }

        SchoolUserAccount account = schoolUserAccountService.getOne(new LambdaQueryWrapper<SchoolUserAccount>()
                .eq(SchoolUserAccount::getUsername, request.getUsername().trim())
                .last("LIMIT 1"));
        if (account == null) {
            throw new IllegalArgumentException("账号不存在");
        }
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new IllegalArgumentException("账号未处于启用状态");
        }
        if (!passwordEncoder.matches(request.getPassword(), account.getPasswordHash())) {
            throw new IllegalArgumentException("密码错误");
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
            throw new IllegalArgumentException("档案请求不能为空");
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
            throw new IllegalArgumentException("currentPassword 和 newPassword 不能为空");
        }
        if (!passwordEncoder.matches(request.getCurrentPassword(), account.getPasswordHash())) {
            throw new IllegalArgumentException("当前密码错误");
        }
        if (request.getNewPassword().length() < 6 || request.getNewPassword().length() > 128) {
            throw new IllegalArgumentException("新密码长度必须在 6 到 128 个字符之间");
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
            throw new IllegalArgumentException("需要完成身份认证");
        }
        return account;
    }

    private AuthCurrentUserVO buildCurrentUser(SchoolUserAccount account) {
        return userFactory.build(account);
    }

    private void ensureUsernameAvailable(String username) {
        String cleanUsername = username.trim();
        if (schoolUserAccountService.count(new LambdaQueryWrapper<SchoolUserAccount>()
                .eq(SchoolUserAccount::getUsername, cleanUsername)) > 0) {
            throw new IllegalArgumentException("用户名已存在");
        }
    }

    private String clean(String value) {
        return value == null ? null : value.trim();
    }

    private String cleanWithLimit(String value, int maxLength, String fieldName) {
        String cleaned = clean(value);
        if (cleaned != null && cleaned.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " 长度不能超过 " + maxLength + " 个字符");
        }
        return StringUtils.hasText(cleaned) ? cleaned : null;
    }
}
