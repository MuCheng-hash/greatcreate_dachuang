package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.service.UserManagementService;
import com.redculture.platform.vo.*;
import com.redculture.platform.vo.request.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class UserManagementAdminController {

    private final UserManagementService userManagementService;

    public UserManagementAdminController(UserManagementService userManagementService) {
        this.userManagementService = userManagementService;
    }

    @GetMapping("/user-accounts")
    public ApiResponse<PageResult<UserAccountAdminVO>> pageAccounts(@RequestParam(required = false) String keyword,
                                                                    @RequestParam(required = false) Long schoolId,
                                                                    @RequestParam(required = false) String status,
                                                                    @RequestParam(required = false) Long pageNum,
                                                                    @RequestParam(required = false) Long pageSize) {
        return ApiResponse.success(userManagementService.pageAccounts(keyword, schoolId, status, pageNum, pageSize));
    }

    @PostMapping("/user-accounts")
    public ApiResponse<UserAccountAdminVO> createAccount(@RequestBody UserAccountCreateRequest request) {
        try {
            return ApiResponse.success("account created", userManagementService.createAccount(request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PutMapping("/user-accounts/{accountId}")
    public ApiResponse<UserAccountAdminVO> updateAccount(@PathVariable Long accountId,
                                                        @RequestBody UserAccountUpdateRequest request) {
        try {
            return ApiResponse.success("account updated", userManagementService.updateAccount(accountId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PutMapping("/user-accounts/{accountId}/status")
    public ApiResponse<UserAccountAdminVO> updateAccountStatus(@PathVariable Long accountId,
                                                              @RequestBody UserAccountStatusRequest request) {
        try {
            return ApiResponse.success("account status updated", userManagementService.updateAccountStatus(accountId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PostMapping("/user-accounts/{accountId}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable Long accountId,
                                           @RequestBody UserAccountResetPasswordRequest request) {
        try {
            userManagementService.resetPassword(accountId, request);
            return ApiResponse.success("password reset", null);
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PutMapping("/user-accounts/{accountId}/roles")
    public ApiResponse<UserAccountAdminVO> assignRoles(@PathVariable Long accountId,
                                                      @RequestBody UserAccountRoleAssignRequest request) {
        try {
            return ApiResponse.success("account roles updated", userManagementService.assignRoles(accountId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @GetMapping("/user-profiles")
    public ApiResponse<PageResult<UserProfileAdminVO>> pageProfiles(@RequestParam(required = false) String keyword,
                                                                    @RequestParam(required = false) String profileType,
                                                                    @RequestParam(required = false) Long schoolId,
                                                                    @RequestParam(required = false) Long classId,
                                                                    @RequestParam(required = false) Long pageNum,
                                                                    @RequestParam(required = false) Long pageSize) {
        return ApiResponse.success(userManagementService.pageProfiles(keyword, profileType, schoolId, classId, pageNum, pageSize));
    }

    @PostMapping("/user-profiles")
    public ApiResponse<UserProfileAdminVO> saveProfile(@RequestBody UserProfileSaveRequest request) {
        try {
            return ApiResponse.success("profile saved", userManagementService.saveProfile(request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PutMapping("/user-profiles/{profileId}")
    public ApiResponse<UserProfileAdminVO> updateProfile(@PathVariable Long profileId,
                                                        @RequestBody UserProfileSaveRequest request) {
        try {
            return ApiResponse.success("profile updated", userManagementService.updateProfile(profileId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @GetMapping("/roles")
    public ApiResponse<List<RoleAdminVO>> listRoles() {
        return ApiResponse.success(userManagementService.listRoles());
    }

    @PostMapping("/roles")
    public ApiResponse<RoleAdminVO> createRole(@RequestBody RoleSaveRequest request) {
        try {
            return ApiResponse.success("role created", userManagementService.createRole(request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PutMapping("/roles/{roleId}")
    public ApiResponse<RoleAdminVO> updateRole(@PathVariable Long roleId,
                                              @RequestBody RoleSaveRequest request) {
        try {
            return ApiResponse.success("role updated", userManagementService.updateRole(roleId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PutMapping("/roles/{roleId}/permissions")
    public ApiResponse<RoleAdminVO> assignRolePermissions(@PathVariable Long roleId,
                                                         @RequestBody RolePermissionAssignRequest request) {
        try {
            return ApiResponse.success("role permissions updated", userManagementService.assignRolePermissions(roleId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @GetMapping("/permissions")
    public ApiResponse<List<PermissionAdminVO>> listPermissions() {
        return ApiResponse.success(userManagementService.listPermissions());
    }

    @GetMapping("/classes")
    public ApiResponse<List<ClassInfoAdminVO>> listClasses(@RequestParam(required = false) Long schoolId) {
        return ApiResponse.success(userManagementService.listClasses(schoolId));
    }

    @PostMapping("/students/import")
    public ApiResponse<StudentImportResultVO> importStudents(@RequestBody StudentImportRequest request) {
        return ApiResponse.success("students imported", userManagementService.importStudents(request));
    }
}
