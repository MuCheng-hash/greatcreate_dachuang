package com.redculture.platform.service;

import com.redculture.platform.common.PageResult;
import com.redculture.platform.vo.*;
import com.redculture.platform.vo.request.*;

import java.util.List;

public interface UserManagementService {
    PageResult<UserAccountAdminVO> pageAccounts(String keyword, Long schoolId, String status, Long pageNum, Long pageSize);
    UserAccountAdminVO createAccount(UserAccountCreateRequest request);
    UserAccountAdminVO updateAccount(Long accountId, UserAccountUpdateRequest request);
    UserAccountAdminVO updateAccountStatus(Long accountId, UserAccountStatusRequest request);
    void resetPassword(Long accountId, UserAccountResetPasswordRequest request);
    UserAccountAdminVO assignRoles(Long accountId, UserAccountRoleAssignRequest request);

    PageResult<UserProfileAdminVO> pageProfiles(String keyword, String profileType, Long schoolId, Long classId, Long pageNum, Long pageSize);
    UserProfileAdminVO saveProfile(UserProfileSaveRequest request);
    UserProfileAdminVO updateProfile(Long profileId, UserProfileSaveRequest request);

    List<RoleAdminVO> listRoles();
    RoleAdminVO createRole(RoleSaveRequest request);
    RoleAdminVO updateRole(Long roleId, RoleSaveRequest request);
    RoleAdminVO assignRolePermissions(Long roleId, RolePermissionAssignRequest request);
    List<PermissionAdminVO> listPermissions();
    List<ClassInfoAdminVO> listClasses(Long schoolId);

    StudentImportResultVO importStudents(StudentImportRequest request);
}
