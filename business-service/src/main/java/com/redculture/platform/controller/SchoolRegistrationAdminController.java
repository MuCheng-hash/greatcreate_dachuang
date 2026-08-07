package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.enums.RegistrationReviewStatus;
import com.redculture.platform.exception.AuthNotFoundException;
import com.redculture.platform.service.SchoolRegistrationService;
import com.redculture.platform.vo.SchoolRegistrationAdminVO;
import com.redculture.platform.vo.request.RegistrationReviewRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/registrations")
//管理员审核学校注册申请：查询申请、查看详情、通过或拒绝。
public class SchoolRegistrationAdminController {

    private final SchoolRegistrationService schoolRegistrationService;

    public SchoolRegistrationAdminController(SchoolRegistrationService schoolRegistrationService) {
        this.schoolRegistrationService = schoolRegistrationService;
    }

    //分页查询学校注册申请。
    @GetMapping
    public ApiResponse<PageResult<SchoolRegistrationAdminVO>> page(@RequestParam(required = false) String keyword,
                                                                   @RequestParam(required = false) RegistrationReviewStatus reviewStatus,
                                                                   @RequestParam(required = false) Long pageNum,
                                                                   @RequestParam(required = false) Long pageSize) {
        return ApiResponse.success(schoolRegistrationService.pageRegistrations(keyword, reviewStatus, pageNum, pageSize));
    }

    //查看某条注册申请的完整详情。
    @GetMapping("/{registrationId}")
    public ApiResponse<SchoolRegistrationAdminVO> detail(@PathVariable Long registrationId) {
        SchoolRegistrationAdminVO data = schoolRegistrationService.getRegistrationDetail(registrationId);
        if (data == null) {
            throw new AuthNotFoundException("注册申请不存在");
        }
        return ApiResponse.success(data);
    }

    //审核通过一条注册申请。
    @PostMapping("/{registrationId}/approve")
    public ApiResponse<SchoolRegistrationAdminVO> approve(@PathVariable Long registrationId,
                                                          @RequestBody(required = false) RegistrationReviewRequest request) {
        return ApiResponse.success("registration approved", schoolRegistrationService.approveRegistration(registrationId, request));
    }

    //驳回一条注册申请。
    @PostMapping("/{registrationId}/reject")
    public ApiResponse<SchoolRegistrationAdminVO> reject(@PathVariable Long registrationId,
                                                         @RequestBody(required = false) RegistrationReviewRequest request) {
        return ApiResponse.success("registration rejected", schoolRegistrationService.rejectRegistration(registrationId, request));
    }
}
