package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.service.admin.AdminDashboardService;
import com.redculture.platform.vo.admin.AdminDashboardOverviewVO;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminDashboardControllerTest {

    @Test
    void exposesOverviewResponseWithoutChangingExistingEndpoint() {
        AdminDashboardService service = mock(AdminDashboardService.class);
        AdminDashboardOverviewVO overview = new AdminDashboardOverviewVO();
        overview.setResourceCount(12L);
        overview.setSchoolCount(3L);
        when(service.overview()).thenReturn(Mono.just(overview));

        ApiResponse<AdminDashboardOverviewVO> response =
                new AdminDashboardController(service).overview().block();

        assertEquals(200, response.getCode());
        assertEquals(12L, response.getData().getResourceCount());
        assertEquals(3L, response.getData().getSchoolCount());
    }
}
