package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.service.UserManagementService;
import com.redculture.platform.mapper.SchoolMapper;
import com.redculture.platform.entity.School;
import com.redculture.platform.entity.ClassInfo;
import com.redculture.platform.mapper.ClassInfoMapper;
import com.redculture.platform.vo.*;
import com.redculture.platform.vo.request.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.apache.poi.ss.usermodel.*;
import java.util.ArrayList;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import java.util.List;

@RestController
@RequestMapping({"/api/admin", "/admin"})
//用户与权限后台：账号、个人档案、角色、权限、班级管理，以及学生批量导入。
public class UserManagementAdminController {

    private final UserManagementService userManagementService;
    private final SchoolMapper schoolMapper;
    private final ClassInfoMapper classInfoMapper;

    public UserManagementAdminController(UserManagementService userManagementService, SchoolMapper schoolMapper, ClassInfoMapper classInfoMapper) {
        this.userManagementService = userManagementService;
        this.schoolMapper = schoolMapper;
        this.classInfoMapper = classInfoMapper;
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

    @PostMapping("/students/import-excel")
    public ApiResponse<StudentImportResultVO> importStudentsExcel(@RequestPart("file") MultipartFile file) {
        try {
            if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择 Excel 文件");
            List<StudentImportRowRequest> rows = new ArrayList<>();
            if (file.getOriginalFilename() != null && file.getOriginalFilename().toLowerCase().endsWith(".csv")) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
                    String line; boolean header = true;
                    while ((line = reader.readLine()) != null) { if (header) { header = false; continue; } String[] c = line.split(",", -1); if (c.length < 3) continue; StudentImportRowRequest item = new StudentImportRowRequest(); item.setUsername(c[0].trim()); item.setPassword(c.length > 1 ? c[1].trim() : ""); item.setRealName(c.length > 2 ? c[2].trim() : ""); item.setStudentNo(c.length > 3 ? c[3].trim() : ""); item.setSchoolId(resolveSchoolId(c.length > 4 ? c[4] : "")); item.setClassId(resolveClassId(item.getSchoolId(), c.length > 5 ? c[5] : "")); item.setGradeName(c.length > 6 ? c[6].trim() : ""); item.setPhone(c.length > 7 ? c[7].trim() : ""); item.setEmail(c.length > 8 ? c[8].trim() : ""); if (!item.getUsername().isBlank()) rows.add(item); }
                }
            } else try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
                Sheet sheet = workbook.getSheetAt(0);
                DataFormatter formatter = new DataFormatter();
                for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i); if (row == null) continue;
                    StudentImportRowRequest item = new StudentImportRowRequest();
                    item.setUsername(formatter.formatCellValue(row.getCell(0)).trim());
                    item.setPassword(formatter.formatCellValue(row.getCell(1)).trim());
                    item.setRealName(formatter.formatCellValue(row.getCell(2)).trim());
                    item.setStudentNo(formatter.formatCellValue(row.getCell(3)).trim());
                    item.setSchoolId(resolveSchoolId(formatter.formatCellValue(row.getCell(4))));
                    item.setClassId(resolveClassId(item.getSchoolId(), formatter.formatCellValue(row.getCell(5))));
                    item.setGradeName(formatter.formatCellValue(row.getCell(6)).trim());
                    item.setPhone(formatter.formatCellValue(row.getCell(7)).trim());
                    item.setEmail(formatter.formatCellValue(row.getCell(8)).trim());
                    if (item.getUsername() != null && !item.getUsername().isBlank()) rows.add(item);
                }
            }
            StudentImportRequest request = new StudentImportRequest(); request.setRows(rows);
            return ApiResponse.success("students imported", userManagementService.importStudents(request));
        } catch (Exception exception) { return ApiResponse.fail("Excel 导入失败：" + exception.getMessage()); }
    }

    private static Long parseLong(String value) { try { return value == null || value.isBlank() ? null : Long.valueOf(value.replaceAll("\\.0$", "")); } catch (NumberFormatException ignored) { return null; } }
    private Long resolveSchoolId(String name) { if (name == null || name.isBlank()) return null; School school = schoolMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<School>().eq(School::getSchoolName, name.trim()).last("LIMIT 1")); if (school == null) throw new IllegalArgumentException("找不到学校：" + name); return school.getSchoolId(); }
    private Long resolveClassId(Long schoolId, String name) { if (name == null || name.isBlank()) return null; if (schoolId == null) throw new IllegalArgumentException("班级所属学校不能为空"); ClassInfo info = classInfoMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ClassInfo>().eq(ClassInfo::getSchoolId, schoolId).eq(ClassInfo::getClassName, name.trim()).last("LIMIT 1")); if (info == null) throw new IllegalArgumentException("找不到班级：" + name); return info.getClassId(); }
}
