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
//用户与权限后台：账号、个人档案、角色、权限、班级管理，以及学生批量导入。
public class UserManagementAdminController {

    private final UserManagementService userManagementService;

    public UserManagementAdminController(UserManagementService userManagementService) {
        this.userManagementService = userManagementService;
    }

    //分页查询账号。
    @GetMapping("/user-accounts")
    public ApiResponse<PageResult<UserAccountAdminVO>> pageAccounts(@RequestParam(required = false) String keyword,
                                                                    @RequestParam(required = false) Long schoolId,
                                                                    @RequestParam(required = false) String status,
                                                                    @RequestParam(required = false) Long pageNum,
                                                                    @RequestParam(required = false) Long pageSize) {
        return ApiResponse.success(userManagementService.pageAccounts(keyword, schoolId, status, pageNum, pageSize));
    }

    //新建账号。
    @PostMapping("/user-accounts")
    public ApiResponse<UserAccountAdminVO> createAccount(@RequestBody UserAccountCreateRequest request) {
        try {
            return ApiResponse.success("account created", userManagementService.createAccount(request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    //修改账号基本信息。
    @PutMapping("/user-accounts/{accountId}")
    public ApiResponse<UserAccountAdminVO> updateAccount(@PathVariable Long accountId,
                                                        @RequestBody UserAccountUpdateRequest request) {
        try {
            return ApiResponse.success("account updated", userManagementService.updateAccount(accountId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    //启用、停用或锁定账号。
    @PutMapping("/user-accounts/{accountId}/status")
    public ApiResponse<UserAccountAdminVO> updateAccountStatus(@PathVariable Long accountId,
                                                              @RequestBody UserAccountStatusRequest request) {
        try {
            return ApiResponse.success("account status updated", userManagementService.updateAccountStatus(accountId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    //管理员重置指定账号密码
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

    //为账号分配角色。
    @PutMapping("/user-accounts/{accountId}/roles")
    public ApiResponse<UserAccountAdminVO> assignRoles(@PathVariable Long accountId,
                                                      @RequestBody UserAccountRoleAssignRequest request) {
        try {
            return ApiResponse.success("account roles updated", userManagementService.assignRoles(accountId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    //分页查询教师、学生等个人档案。
    @GetMapping("/user-profiles")
    public ApiResponse<PageResult<UserProfileAdminVO>> pageProfiles(@RequestParam(required = false) String keyword,
                                                                    @RequestParam(required = false) String profileType,
                                                                    @RequestParam(required = false) Long schoolId,
                                                                    @RequestParam(required = false) Long classId,
                                                                    @RequestParam(required = false) Long pageNum,
                                                                    @RequestParam(required = false) Long pageSize) {
        return ApiResponse.success(userManagementService.pageProfiles(keyword, profileType, schoolId, classId, pageNum, pageSize));
    }

    //新增或保存个人档案。
    @PostMapping("/user-profiles")
    public ApiResponse<UserProfileAdminVO> saveProfile(@RequestBody UserProfileSaveRequest request) {
        try {
            return ApiResponse.success("profile saved", userManagementService.saveProfile(request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    //修改个人档案。
    @PutMapping("/user-profiles/{profileId}")
    public ApiResponse<UserProfileAdminVO> updateProfile(@PathVariable Long profileId,
                                                        @RequestBody UserProfileSaveRequest request) {
        try {
            return ApiResponse.success("profile updated", userManagementService.updateProfile(profileId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    //查询全部角色。
    @GetMapping("/roles")
    public ApiResponse<List<RoleAdminVO>> listRoles() {
        return ApiResponse.success(userManagementService.listRoles());
    }

    //新建角色。
    @PostMapping("/roles")
    public ApiResponse<RoleAdminVO> createRole(@RequestBody RoleSaveRequest request) {
        try {
            return ApiResponse.success("role created", userManagementService.createRole(request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    //修改角色基本信息。
    @PutMapping("/roles/{roleId}")
    public ApiResponse<RoleAdminVO> updateRole(@PathVariable Long roleId,
                                              @RequestBody RoleSaveRequest request) {
        try {
            return ApiResponse.success("role updated", userManagementService.updateRole(roleId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    //为角色分配权限。
    @PutMapping("/roles/{roleId}/permissions")
    public ApiResponse<RoleAdminVO> assignRolePermissions(@PathVariable Long roleId,
                                                         @RequestBody RolePermissionAssignRequest request) {
        try {
            return ApiResponse.success("role permissions updated", userManagementService.assignRolePermissions(roleId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }


    //查询全部可分配权限。
    @GetMapping("/permissions")
    public ApiResponse<List<PermissionAdminVO>> listPermissions() {
        return ApiResponse.success(userManagementService.listPermissions());
    }

    //查询班级，可按学校筛选。
    @GetMapping("/classes")
    public ApiResponse<List<ClassInfoAdminVO>> listClasses(@RequestParam(required = false) Long schoolId) {
        return ApiResponse.success(userManagementService.listClasses(schoolId));
    }

    //批量导入学生资料。
    @PostMapping("/students/import")
    public ApiResponse<StudentImportResultVO> importStudents(@RequestBody StudentImportRequest request) {
        return ApiResponse.success("students imported", userManagementService.importStudents(request));
    }
}
