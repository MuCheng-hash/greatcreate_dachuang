package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.entity.*;
import com.redculture.platform.enums.AccountStatus;
import com.redculture.platform.mapper.*;
import com.redculture.platform.service.SchoolUserAccountService;
import com.redculture.platform.service.UserManagementService;
import com.redculture.platform.vo.*;
import com.redculture.platform.vo.request.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class UserManagementServiceImpl implements UserManagementService {

    private static final long DEFAULT_PAGE_NUM = 1L;
    private static final long DEFAULT_PAGE_SIZE = 10L;
    private static final long MAX_PAGE_SIZE = 100L;

    private final SchoolUserAccountService accountService;
    private final UserProfileMapper userProfileMapper;
    private final TeacherProfileMapper teacherProfileMapper;
    private final StudentProfileMapper studentProfileMapper;
    private final ClassInfoMapper classInfoMapper;
    private final ClassMemberMapper classMemberMapper;
    private final ClassTeacherMapper classTeacherMapper;
    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;
    private final SysAccountRoleMapper accountRoleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SchoolMapper schoolMapper;
    private final PasswordEncoder passwordEncoder;

    public UserManagementServiceImpl(SchoolUserAccountService accountService,
                                     UserProfileMapper userProfileMapper,
                                     TeacherProfileMapper teacherProfileMapper,
                                     StudentProfileMapper studentProfileMapper,
                                     ClassInfoMapper classInfoMapper,
                                     ClassMemberMapper classMemberMapper,
                                     ClassTeacherMapper classTeacherMapper,
                                     SysRoleMapper roleMapper,
                                     SysPermissionMapper permissionMapper,
                                     SysAccountRoleMapper accountRoleMapper,
                                     SysRolePermissionMapper rolePermissionMapper,
                                     SchoolMapper schoolMapper,
                                     PasswordEncoder passwordEncoder) {
        this.accountService = accountService;
        this.userProfileMapper = userProfileMapper;
        this.teacherProfileMapper = teacherProfileMapper;
        this.studentProfileMapper = studentProfileMapper;
        this.classInfoMapper = classInfoMapper;
        this.classMemberMapper = classMemberMapper;
        this.classTeacherMapper = classTeacherMapper;
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.accountRoleMapper = accountRoleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.schoolMapper = schoolMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public PageResult<UserAccountAdminVO> pageAccounts(String keyword, Long schoolId, String status, Long pageNum, Long pageSize) {
        long safePageNum = safePageNum(pageNum);
        long safePageSize = safePageSize(pageSize);
        LambdaQueryWrapper<SchoolUserAccount> wrapper = new LambdaQueryWrapper<SchoolUserAccount>()
                .eq(schoolId != null, SchoolUserAccount::getSchoolId, schoolId)
                .eq(StringUtils.hasText(status), SchoolUserAccount::getStatus, parseStatus(status))
                .orderByDesc(SchoolUserAccount::getCreatedAt);
        if (StringUtils.hasText(keyword)) {
            String cleanKeyword = keyword.trim();
            wrapper.and(item -> item.like(SchoolUserAccount::getUsername, cleanKeyword)
                    .or().like(SchoolUserAccount::getDisplayName, cleanKeyword)
                    .or().like(SchoolUserAccount::getRealName, cleanKeyword)
                    .or().like(SchoolUserAccount::getContactPhone, cleanKeyword));
        }
        Page<SchoolUserAccount> page = accountService.page(new Page<>(safePageNum, safePageSize), wrapper);
        return PageResult.of(toAccountVOs(page.getRecords()), page.getTotal(), safePageNum, safePageSize);
    }

    @Override
    @Transactional
    public UserAccountAdminVO createAccount(UserAccountCreateRequest request) {
        if (request == null || !StringUtils.hasText(request.getUsername())) {
            throw new IllegalArgumentException("username is required");
        }
        if (!StringUtils.hasText(request.getPassword()) || request.getPassword().length() < 6) {
            throw new IllegalArgumentException("password must be at least 6 characters");
        }
        ensureUsernameAvailable(request.getUsername(), null);
        SchoolUserAccount account = new SchoolUserAccount();
        account.setUsername(clean(request.getUsername()));
        account.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        account.setDisplayName(clean(request.getDisplayName()));
        account.setRealName(clean(request.getRealName()));
        account.setContactName(clean(request.getRealName()));
        account.setContactPhone(clean(request.getContactPhone()));
        account.setEmail(clean(request.getEmail()));
        account.setSchoolId(request.getSchoolId());
        account.setStatus(AccountStatus.ACTIVE);
        account.setForcePasswordChange(false);
        account.setPasswordUpdatedAt(LocalDateTime.now());
        account.setRoleCode(firstRoleCode(request.getRoleIds()));
        accountService.save(account);
        replaceAccountRoles(account.getAccountId(), request.getRoleIds(), "school");
        return toAccountVO(account);
    }

    @Override
    @Transactional
    public UserAccountAdminVO updateAccount(Long accountId, UserAccountUpdateRequest request) {
        SchoolUserAccount account = requireAccount(accountId);
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        account.setDisplayName(valueOrOriginal(request.getDisplayName(), account.getDisplayName()));
        account.setRealName(valueOrOriginal(request.getRealName(), account.getRealName()));
        account.setContactName(valueOrOriginal(request.getRealName(), account.getContactName()));
        account.setContactPhone(valueOrOriginal(request.getContactPhone(), account.getContactPhone()));
        account.setEmail(valueOrOriginal(request.getEmail(), account.getEmail()));
        if (request.getSchoolId() != null) {
            account.setSchoolId(request.getSchoolId());
        }
        accountService.updateById(account);
        syncProfileFromAccount(account);
        return toAccountVO(accountService.getById(accountId));
    }

    @Override
    @Transactional
    public UserAccountAdminVO updateAccountStatus(Long accountId, UserAccountStatusRequest request) {
        SchoolUserAccount account = requireAccount(accountId);
        account.setStatus(parseStatus(request == null ? null : request.getStatus()));
        accountService.updateById(account);
        return toAccountVO(accountService.getById(accountId));
    }

    @Override
    @Transactional
    public void resetPassword(Long accountId, UserAccountResetPasswordRequest request) {
        SchoolUserAccount account = requireAccount(accountId);
        String password = request == null ? null : request.getPassword();
        if (!StringUtils.hasText(password) || password.length() < 6) {
            throw new IllegalArgumentException("password must be at least 6 characters");
        }
        account.setPasswordHash(passwordEncoder.encode(password));
        account.setForcePasswordChange(Boolean.TRUE.equals(request.getForcePasswordChange()));
        account.setPasswordUpdatedAt(LocalDateTime.now());
        accountService.updateById(account);
    }

    @Override
    @Transactional
    public UserAccountAdminVO assignRoles(Long accountId, UserAccountRoleAssignRequest request) {
        SchoolUserAccount account = requireAccount(accountId);
        List<Long> roleIds = request == null ? Collections.emptyList() : request.getRoleIds();
        replaceAccountRoles(accountId, roleIds, request == null ? null : request.getDataScope());
        account.setRoleCode(firstRoleCode(roleIds));
        accountService.updateById(account);
        return toAccountVO(accountService.getById(accountId));
    }

    @Override
    public PageResult<UserProfileAdminVO> pageProfiles(String keyword, String profileType, Long schoolId, Long classId, Long pageNum, Long pageSize) {
        long safePageNum = safePageNum(pageNum);
        long safePageSize = safePageSize(pageSize);
        LambdaQueryWrapper<UserProfile> wrapper = new LambdaQueryWrapper<UserProfile>()
                .eq(StringUtils.hasText(profileType), UserProfile::getProfileType, clean(profileType))
                .eq(schoolId != null, UserProfile::getSchoolId, schoolId)
                .orderByDesc(UserProfile::getCreatedAt);
        if (StringUtils.hasText(keyword)) {
            String cleanKeyword = keyword.trim();
            wrapper.and(item -> item.like(UserProfile::getRealName, cleanKeyword)
                    .or().like(UserProfile::getPhone, cleanKeyword)
                    .or().like(UserProfile::getEmail, cleanKeyword));
        }
        Page<UserProfile> page = userProfileMapper.selectPage(new Page<>(safePageNum, safePageSize), wrapper);
        List<UserProfileAdminVO> records = toProfileVOs(page.getRecords());
        if (classId != null) {
            records = records.stream().filter(item -> item.getClassIds().contains(classId)).toList();
        }
        return PageResult.of(records, classId == null ? page.getTotal() : records.size(), safePageNum, safePageSize);
    }

    @Override
    @Transactional
    public UserProfileAdminVO saveProfile(UserProfileSaveRequest request) {
        validateProfileRequest(request);
        if (userProfileMapper.selectCount(new LambdaQueryWrapper<UserProfile>()
                .eq(UserProfile::getAccountId, request.getAccountId())) > 0) {
            throw new IllegalArgumentException("account already has a profile");
        }
        UserProfile profile = new UserProfile();
        fillProfile(profile, request);
        userProfileMapper.insert(profile);
        upsertProfileExtension(profile, request);
        syncAccountFromProfile(profile);
        return toProfileVO(profile);
    }

    @Override
    @Transactional
    public UserProfileAdminVO updateProfile(Long profileId, UserProfileSaveRequest request) {
        UserProfile profile = userProfileMapper.selectById(profileId);
        if (profile == null) {
            throw new IllegalArgumentException("profile not found");
        }
        fillProfile(profile, request);
        userProfileMapper.updateById(profile);
        upsertProfileExtension(profile, request);
        syncAccountFromProfile(profile);
        return toProfileVO(userProfileMapper.selectById(profileId));
    }

    @Override
    public List<RoleAdminVO> listRoles() {
        return roleMapper.selectList(new LambdaQueryWrapper<SysRole>().orderByAsc(SysRole::getRoleId))
                .stream().map(this::toRoleVO).toList();
    }

    @Override
    @Transactional
    public RoleAdminVO createRole(RoleSaveRequest request) {
        if (request == null || !StringUtils.hasText(request.getRoleCode()) || !StringUtils.hasText(request.getRoleName())) {
            throw new IllegalArgumentException("roleCode and roleName are required");
        }
        SysRole role = new SysRole();
        role.setRoleCode(clean(request.getRoleCode()));
        role.setRoleName(clean(request.getRoleName()));
        role.setRoleScope(StringUtils.hasText(request.getRoleScope()) ? clean(request.getRoleScope()) : "school");
        role.setStatus(StringUtils.hasText(request.getStatus()) ? clean(request.getStatus()) : "active");
        role.setSystemRole(false);
        roleMapper.insert(role);
        return toRoleVO(role);
    }

    @Override
    @Transactional
    public RoleAdminVO updateRole(Long roleId, RoleSaveRequest request) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new IllegalArgumentException("role not found");
        }
        role.setRoleName(valueOrOriginal(request.getRoleName(), role.getRoleName()));
        role.setRoleScope(valueOrOriginal(request.getRoleScope(), role.getRoleScope()));
        role.setStatus(valueOrOriginal(request.getStatus(), role.getStatus()));
        roleMapper.updateById(role);
        return toRoleVO(roleMapper.selectById(roleId));
    }

    @Override
    @Transactional
    public RoleAdminVO assignRolePermissions(Long roleId, RolePermissionAssignRequest request) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new IllegalArgumentException("role not found");
        }
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>()
                .eq(SysRolePermission::getRoleId, roleId));
        String dataScope = StringUtils.hasText(request == null ? null : request.getDataScope())
                ? request.getDataScope().trim() : "school";
        List<Long> permissionIds = request == null ? Collections.emptyList() : request.getPermissionIds();
        if (permissionIds != null) {
            for (Long permissionId : permissionIds) {
                if (permissionId == null || permissionMapper.selectById(permissionId) == null) continue;
                SysRolePermission rel = new SysRolePermission();
                rel.setRoleId(roleId);
                rel.setPermissionId(permissionId);
                rel.setDataScope(dataScope);
                rolePermissionMapper.insert(rel);
            }
        }
        return toRoleVO(roleMapper.selectById(roleId));
    }

    @Override
    public List<PermissionAdminVO> listPermissions() {
        return permissionMapper.selectList(new LambdaQueryWrapper<SysPermission>().orderByAsc(SysPermission::getSortOrder))
                .stream().map(this::toPermissionVO).toList();
    }

    @Override
    public List<ClassInfoAdminVO> listClasses(Long schoolId) {
        LambdaQueryWrapper<ClassInfo> wrapper = new LambdaQueryWrapper<ClassInfo>()
                .eq(schoolId != null, ClassInfo::getSchoolId, schoolId)
                .orderByAsc(ClassInfo::getSchoolId)
                .orderByAsc(ClassInfo::getGradeName)
                .orderByAsc(ClassInfo::getClassName);
        return toClassVOs(classInfoMapper.selectList(wrapper));
    }

    @Override
    @Transactional
    public StudentImportResultVO importStudents(StudentImportRequest request) {
        StudentImportResultVO result = new StudentImportResultVO();
        List<StudentImportRowRequest> rows = request == null ? Collections.emptyList() : request.getRows();
        for (int index = 0; index < rows.size(); index++) {
            StudentImportRowRequest row = rows.get(index);
            try {
                UserAccountCreateRequest accountRequest = new UserAccountCreateRequest();
                accountRequest.setUsername(row.getUsername());
                accountRequest.setPassword(StringUtils.hasText(row.getPassword()) ? row.getPassword() : "123456");
                accountRequest.setRealName(row.getRealName());
                accountRequest.setDisplayName(row.getRealName());
                accountRequest.setContactPhone(row.getPhone());
                accountRequest.setEmail(row.getEmail());
                accountRequest.setSchoolId(row.getSchoolId());
                accountRequest.setRoleIds(roleIdsByCodes(List.of("student")));
                UserAccountAdminVO account = createAccount(accountRequest);

                UserProfileSaveRequest profileRequest = new UserProfileSaveRequest();
                profileRequest.setAccountId(account.getAccountId());
                profileRequest.setProfileType("student");
                profileRequest.setRealName(row.getRealName());
                profileRequest.setPhone(row.getPhone());
                profileRequest.setEmail(row.getEmail());
                profileRequest.setSchoolId(row.getSchoolId());
                profileRequest.setStudentNo(row.getStudentNo());
                profileRequest.setGradeName(row.getGradeName());
                profileRequest.setEnrollmentYear(row.getEnrollmentYear());
                profileRequest.setClassIds(row.getClassId() == null ? Collections.emptyList() : List.of(row.getClassId()));
                saveProfile(profileRequest);
                result.setSuccessCount(result.getSuccessCount() + 1);
            } catch (RuntimeException exception) {
                result.setFailedCount(result.getFailedCount() + 1);
                result.getErrors().add("第 " + (index + 1) + " 行：" + exception.getMessage());
            }
        }
        return result;
    }

    private void validateProfileRequest(UserProfileSaveRequest request) {
        if (request == null || request.getAccountId() == null) {
            throw new IllegalArgumentException("accountId is required");
        }
        if (!StringUtils.hasText(request.getProfileType())) {
            throw new IllegalArgumentException("profileType is required");
        }
        if (!StringUtils.hasText(request.getRealName())) {
            throw new IllegalArgumentException("realName is required");
        }
        requireAccount(request.getAccountId());
    }

    private void fillProfile(UserProfile profile, UserProfileSaveRequest request) {
        if (request.getAccountId() != null) {
            profile.setAccountId(request.getAccountId());
        }
        profile.setProfileType(valueOrOriginal(request.getProfileType(), profile.getProfileType()));
        profile.setRealName(valueOrOriginal(request.getRealName(), profile.getRealName()));
        profile.setGender(valueOrOriginal(request.getGender(), profile.getGender()));
        profile.setPhone(valueOrOriginal(request.getPhone(), profile.getPhone()));
        profile.setEmail(valueOrOriginal(request.getEmail(), profile.getEmail()));
        if (request.getSchoolId() != null) {
            profile.setSchoolId(request.getSchoolId());
        }
        profile.setStatus(StringUtils.hasText(request.getStatus()) ? clean(request.getStatus()) : valueOrOriginal(profile.getStatus(), "active"));
        profile.setRemark(valueOrOriginal(request.getRemark(), profile.getRemark()));
    }

    private void upsertProfileExtension(UserProfile profile, UserProfileSaveRequest request) {
        if ("teacher".equalsIgnoreCase(profile.getProfileType())) {
            TeacherProfile teacher = teacherProfileMapper.selectOne(new LambdaQueryWrapper<TeacherProfile>()
                    .eq(TeacherProfile::getProfileId, profile.getProfileId()).last("LIMIT 1"));
            if (teacher == null) {
                teacher = new TeacherProfile();
                teacher.setAccountId(profile.getAccountId());
                teacher.setProfileId(profile.getProfileId());
            }
            teacher.setSchoolId(profile.getSchoolId());
            teacher.setTeacherName(profile.getRealName());
            teacher.setTeacherNo(valueOrOriginal(request.getTeacherNo(), teacher.getTeacherNo()));
            teacher.setTitle(valueOrOriginal(request.getTitle(), teacher.getTitle()));
            teacher.setStatus(profile.getStatus());
            if (teacher.getTeacherId() == null) {
                teacherProfileMapper.insert(teacher);
            } else {
                teacherProfileMapper.updateById(teacher);
            }
            replaceTeacherClasses(teacher.getTeacherId(), request.getClassIds(), request.getTeacherClassRole());
        } else if ("student".equalsIgnoreCase(profile.getProfileType())) {
            StudentProfile student = studentProfileMapper.selectOne(new LambdaQueryWrapper<StudentProfile>()
                    .eq(StudentProfile::getProfileId, profile.getProfileId()).last("LIMIT 1"));
            if (student == null) {
                student = new StudentProfile();
                student.setAccountId(profile.getAccountId());
                student.setProfileId(profile.getProfileId());
            }
            student.setSchoolId(profile.getSchoolId());
            student.setStudentName(profile.getRealName());
            student.setStudentNo(valueOrOriginal(request.getStudentNo(), student.getStudentNo()));
            student.setGradeName(valueOrOriginal(request.getGradeName(), student.getGradeName()));
            student.setEnrollmentYear(request.getEnrollmentYear() == null ? student.getEnrollmentYear() : request.getEnrollmentYear());
            student.setStatus(profile.getStatus());
            if (student.getStudentId() == null) {
                studentProfileMapper.insert(student);
            } else {
                studentProfileMapper.updateById(student);
            }
            replaceStudentClasses(student.getStudentId(), request.getClassIds());
        }
    }

    private void replaceTeacherClasses(Long teacherId, List<Long> classIds, String teacherRole) {
        if (teacherId == null || classIds == null) return;
        classTeacherMapper.delete(new LambdaQueryWrapper<ClassTeacher>().eq(ClassTeacher::getTeacherId, teacherId));
        for (Long classId : classIds) {
            if (classId == null) continue;
            ClassTeacher rel = new ClassTeacher();
            rel.setTeacherId(teacherId);
            rel.setClassId(classId);
            rel.setTeacherRole(StringUtils.hasText(teacherRole) ? teacherRole : "subject_teacher");
            rel.setStatus("active");
            classTeacherMapper.insert(rel);
        }
    }

    private void replaceStudentClasses(Long studentId, List<Long> classIds) {
        if (studentId == null || classIds == null) return;
        classMemberMapper.delete(new LambdaQueryWrapper<ClassMember>().eq(ClassMember::getStudentId, studentId));
        boolean primary = true;
        for (Long classId : classIds) {
            if (classId == null) continue;
            ClassMember rel = new ClassMember();
            rel.setStudentId(studentId);
            rel.setClassId(classId);
            rel.setJoinSource("manual");
            rel.setPrimaryClass(primary);
            rel.setStatus("active");
            classMemberMapper.insert(rel);
            primary = false;
        }
    }

    private void syncAccountFromProfile(UserProfile profile) {
        SchoolUserAccount account = accountService.getById(profile.getAccountId());
        if (account == null) return;
        account.setRealName(profile.getRealName());
        account.setDisplayName(profile.getRealName());
        account.setContactName(profile.getRealName());
        account.setContactPhone(profile.getPhone());
        account.setEmail(profile.getEmail());
        account.setSchoolId(profile.getSchoolId());
        account.setAccountType(profile.getProfileType());
        accountService.updateById(account);
    }

    private void syncProfileFromAccount(SchoolUserAccount account) {
        UserProfile profile = userProfileMapper.selectOne(new LambdaQueryWrapper<UserProfile>()
                .eq(UserProfile::getAccountId, account.getAccountId()).last("LIMIT 1"));
        if (profile == null) return;
        profile.setRealName(StringUtils.hasText(account.getRealName()) ? account.getRealName() : profile.getRealName());
        profile.setPhone(account.getContactPhone());
        profile.setEmail(account.getEmail());
        profile.setSchoolId(account.getSchoolId());
        userProfileMapper.updateById(profile);
    }

    private void replaceAccountRoles(Long accountId, List<Long> roleIds, String dataScope) {
        accountRoleMapper.delete(new LambdaQueryWrapper<SysAccountRole>().eq(SysAccountRole::getAccountId, accountId));
        if (roleIds == null) return;
        String scope = StringUtils.hasText(dataScope) ? dataScope : "school";
        for (Long roleId : roleIds) {
            if (roleId == null || roleMapper.selectById(roleId) == null) continue;
            SysAccountRole rel = new SysAccountRole();
            rel.setAccountId(accountId);
            rel.setRoleId(roleId);
            rel.setDataScope(scope);
            accountRoleMapper.insert(rel);
        }
    }

    private String firstRoleCode(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) return null;
        SysRole role = roleMapper.selectById(roleIds.get(0));
        return role == null ? null : role.getRoleCode();
    }

    private List<Long> roleIdsByCodes(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) return Collections.emptyList();
        return roleMapper.selectList(new LambdaQueryWrapper<SysRole>().in(SysRole::getRoleCode, roleCodes))
                .stream().map(SysRole::getRoleId).toList();
    }

    private List<UserAccountAdminVO> toAccountVOs(List<SchoolUserAccount> accounts) {
        if (accounts.isEmpty()) return Collections.emptyList();
        Map<Long, UserProfile> profileByAccount = userProfileMapper.selectList(new LambdaQueryWrapper<UserProfile>()
                        .in(UserProfile::getAccountId, accounts.stream().map(SchoolUserAccount::getAccountId).toList()))
                .stream().collect(Collectors.toMap(UserProfile::getAccountId, Function.identity(), (a, b) -> a));
        Map<Long, School> schoolById = schoolMap(accounts.stream().map(SchoolUserAccount::getSchoolId).filter(Objects::nonNull).toList());
        Map<Long, List<SysRole>> rolesByAccount = rolesByAccount(accounts.stream().map(SchoolUserAccount::getAccountId).toList());
        return accounts.stream().map(account -> toAccountVO(account, profileByAccount.get(account.getAccountId()),
                schoolById.get(account.getSchoolId()), rolesByAccount.get(account.getAccountId()))).toList();
    }

    private UserAccountAdminVO toAccountVO(SchoolUserAccount account) {
        UserProfile profile = userProfileMapper.selectOne(new LambdaQueryWrapper<UserProfile>()
                .eq(UserProfile::getAccountId, account.getAccountId()).last("LIMIT 1"));
        School school = account.getSchoolId() == null ? null : schoolMapper.selectById(account.getSchoolId());
        return toAccountVO(account, profile, school, rolesByAccount(List.of(account.getAccountId())).get(account.getAccountId()));
    }

    private UserAccountAdminVO toAccountVO(SchoolUserAccount account, UserProfile profile, School school, List<SysRole> roles) {
        UserAccountAdminVO vo = new UserAccountAdminVO();
        vo.setAccountId(account.getAccountId());
        vo.setUsername(account.getUsername());
        vo.setDisplayName(account.getDisplayName());
        vo.setRealName(StringUtils.hasText(account.getRealName()) ? account.getRealName() : account.getContactName());
        vo.setContactPhone(account.getContactPhone());
        vo.setEmail(account.getEmail());
        vo.setStatus(account.getStatus() == null ? null : account.getStatus().getValue());
        vo.setSchoolId(account.getSchoolId());
        vo.setSchoolName(school == null ? null : school.getSchoolName());
        vo.setProfileId(profile == null ? null : profile.getProfileId());
        vo.setProfileType(profile == null ? null : profile.getProfileType());
        vo.setLastLoginAt(account.getLastLoginAt());
        vo.setCreatedAt(account.getCreatedAt());
        if (roles != null) {
            vo.setRoleCodes(roles.stream().map(SysRole::getRoleCode).toList());
            vo.setRoleNames(roles.stream().map(SysRole::getRoleName).toList());
        }
        return vo;
    }

    private List<UserProfileAdminVO> toProfileVOs(List<UserProfile> profiles) {
        return profiles.stream().map(this::toProfileVO).toList();
    }

    private UserProfileAdminVO toProfileVO(UserProfile profile) {
        SchoolUserAccount account = accountService.getById(profile.getAccountId());
        School school = profile.getSchoolId() == null ? null : schoolMapper.selectById(profile.getSchoolId());
        UserProfileAdminVO vo = new UserProfileAdminVO();
        vo.setProfileId(profile.getProfileId());
        vo.setAccountId(profile.getAccountId());
        vo.setUsername(account == null ? null : account.getUsername());
        vo.setProfileType(profile.getProfileType());
        vo.setRealName(profile.getRealName());
        vo.setGender(profile.getGender());
        vo.setPhone(profile.getPhone());
        vo.setEmail(profile.getEmail());
        vo.setSchoolId(profile.getSchoolId());
        vo.setSchoolName(school == null ? null : school.getSchoolName());
        vo.setStatus(profile.getStatus());
        vo.setRemark(profile.getRemark());
        vo.setCreatedAt(profile.getCreatedAt());
        fillProfileExtension(vo, profile);
        return vo;
    }

    private void fillProfileExtension(UserProfileAdminVO vo, UserProfile profile) {
        if ("teacher".equalsIgnoreCase(profile.getProfileType())) {
            TeacherProfile teacher = teacherProfileMapper.selectOne(new LambdaQueryWrapper<TeacherProfile>()
                    .eq(TeacherProfile::getProfileId, profile.getProfileId()).last("LIMIT 1"));
            if (teacher == null) return;
            vo.setTeacherId(teacher.getTeacherId());
            vo.setTeacherNo(teacher.getTeacherNo());
            vo.setTitle(teacher.getTitle());
            List<ClassTeacher> rels = classTeacherMapper.selectList(new LambdaQueryWrapper<ClassTeacher>()
                    .eq(ClassTeacher::getTeacherId, teacher.getTeacherId()));
            applyClassNames(vo, rels.stream().map(ClassTeacher::getClassId).toList());
        } else if ("student".equalsIgnoreCase(profile.getProfileType())) {
            StudentProfile student = studentProfileMapper.selectOne(new LambdaQueryWrapper<StudentProfile>()
                    .eq(StudentProfile::getProfileId, profile.getProfileId()).last("LIMIT 1"));
            if (student == null) return;
            vo.setStudentId(student.getStudentId());
            vo.setStudentNo(student.getStudentNo());
            vo.setGradeName(student.getGradeName());
            vo.setEnrollmentYear(student.getEnrollmentYear());
            List<ClassMember> rels = classMemberMapper.selectList(new LambdaQueryWrapper<ClassMember>()
                    .eq(ClassMember::getStudentId, student.getStudentId()));
            applyClassNames(vo, rels.stream().map(ClassMember::getClassId).toList());
        }
    }

    private void applyClassNames(UserProfileAdminVO vo, List<Long> classIds) {
        vo.setClassIds(classIds);
        if (classIds.isEmpty()) return;
        List<ClassInfo> classes = classInfoMapper.selectBatchIds(classIds);
        vo.setClassNames(classes.stream().map(ClassInfo::getClassName).toList());
    }

    private List<ClassInfoAdminVO> toClassVOs(List<ClassInfo> classes) {
        Map<Long, School> schools = schoolMap(classes.stream().map(ClassInfo::getSchoolId).filter(Objects::nonNull).toList());
        return classes.stream().map(item -> {
            ClassInfoAdminVO vo = new ClassInfoAdminVO();
            vo.setClassId(item.getClassId());
            vo.setSchoolId(item.getSchoolId());
            vo.setSchoolName(Optional.ofNullable(schools.get(item.getSchoolId())).map(School::getSchoolName).orElse(null));
            vo.setClassName(item.getClassName());
            vo.setGradeName(item.getGradeName());
            vo.setClassType(item.getClassType());
            vo.setStatus(item.getStatus());
            return vo;
        }).toList();
    }

    private Map<Long, List<SysRole>> rolesByAccount(List<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) return Collections.emptyMap();
        List<SysAccountRole> rels = accountRoleMapper.selectList(new LambdaQueryWrapper<SysAccountRole>()
                .in(SysAccountRole::getAccountId, accountIds));
        if (rels.isEmpty()) return Collections.emptyMap();
        Map<Long, SysRole> roleById = roleMapper.selectBatchIds(rels.stream().map(SysAccountRole::getRoleId).toList())
                .stream().collect(Collectors.toMap(SysRole::getRoleId, Function.identity()));
        return rels.stream().filter(rel -> roleById.containsKey(rel.getRoleId()))
                .collect(Collectors.groupingBy(SysAccountRole::getAccountId,
                        Collectors.mapping(rel -> roleById.get(rel.getRoleId()), Collectors.toList())));
    }

    private Map<Long, School> schoolMap(List<Long> schoolIds) {
        if (schoolIds == null || schoolIds.isEmpty()) return Collections.emptyMap();
        return schoolMapper.selectBatchIds(schoolIds).stream()
                .collect(Collectors.toMap(School::getSchoolId, Function.identity(), (a, b) -> a));
    }

    private RoleAdminVO toRoleVO(SysRole role) {
        RoleAdminVO vo = new RoleAdminVO();
        vo.setRoleId(role.getRoleId());
        vo.setRoleCode(role.getRoleCode());
        vo.setRoleName(role.getRoleName());
        vo.setRoleScope(role.getRoleScope());
        vo.setSystem(role.getSystemRole());
        vo.setStatus(role.getStatus());
        vo.setPermissionIds(rolePermissionMapper.selectList(new LambdaQueryWrapper<SysRolePermission>()
                        .eq(SysRolePermission::getRoleId, role.getRoleId()))
                .stream().map(SysRolePermission::getPermissionId).toList());
        return vo;
    }

    private PermissionAdminVO toPermissionVO(SysPermission permission) {
        PermissionAdminVO vo = new PermissionAdminVO();
        vo.setPermissionId(permission.getPermissionId());
        vo.setPermissionCode(permission.getPermissionCode());
        vo.setPermissionName(permission.getPermissionName());
        vo.setPermissionType(permission.getPermissionType());
        vo.setResourcePath(permission.getResourcePath());
        vo.setParentId(permission.getParentId());
        vo.setSortOrder(permission.getSortOrder());
        return vo;
    }

    private SchoolUserAccount requireAccount(Long accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("accountId is required");
        }
        SchoolUserAccount account = accountService.getById(accountId);
        if (account == null) {
            throw new IllegalArgumentException("account not found");
        }
        return account;
    }

    private void ensureUsernameAvailable(String username, Long exceptAccountId) {
        LambdaQueryWrapper<SchoolUserAccount> wrapper = new LambdaQueryWrapper<SchoolUserAccount>()
                .eq(SchoolUserAccount::getUsername, clean(username))
                .ne(exceptAccountId != null, SchoolUserAccount::getAccountId, exceptAccountId);
        if (accountService.count(wrapper) > 0) {
            throw new IllegalArgumentException("username already exists");
        }
    }

    private AccountStatus parseStatus(String status) {
        String value = StringUtils.hasText(status) ? status.trim() : "active";
        for (AccountStatus candidate : AccountStatus.values()) {
            if (candidate.getValue().equalsIgnoreCase(value) || candidate.name().equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("unsupported account status: " + status);
    }

    private long safePageNum(Long pageNum) {
        return pageNum == null || pageNum <= 0 ? DEFAULT_PAGE_NUM : pageNum;
    }

    private long safePageSize(Long pageSize) {
        return pageSize == null || pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private String clean(String value) {
        return value == null ? null : value.trim();
    }

    private String valueOrOriginal(String newValue, String originalValue) {
        return newValue == null ? originalValue : clean(newValue);
    }
}
