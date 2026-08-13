package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.service.admin.AdminDashboardService;
import com.redculture.platform.vo.admin.AdminDashboardOverviewVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/admin/dashboard")
public class AdminDashboardController {
    private final AdminDashboardService dashboardService;
    public AdminDashboardController(AdminDashboardService dashboardService) { this.dashboardService = dashboardService; }
    @GetMapping("/overview")
    public Mono<ApiResponse<AdminDashboardOverviewVO>> overview() {
        return dashboardService.overview().map(ApiResponse::success);
    }
}
