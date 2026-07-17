package com.redculture.platform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.entity.RedSite;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.service.RedSiteService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/sites")
public class RedSiteController {

    private final RedSiteService redSiteService;

    public RedSiteController(RedSiteService redSiteService) {
        this.redSiteService = redSiteService;
    }

    @GetMapping("/{id}")
    public ApiResponse<RedSite> getById(@PathVariable Long id) {
        return ApiResponse.success(redSiteService.getById(id));
    }

    @GetMapping
    public ApiResponse<List<RedSite>> list(@RequestParam(required = false) Long regionId,
                                           @RequestParam(required = false) String siteName,
                                           @RequestParam(required = false) ReviewStatus reviewStatus,
                                           @RequestParam(required = false) Boolean active) {
        LambdaQueryWrapper<RedSite> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(regionId != null, RedSite::getRegionId, regionId)
                .like(siteName != null && !siteName.isBlank(), RedSite::getSiteName, siteName)
                .eq(reviewStatus != null, RedSite::getReviewStatus, reviewStatus)
                .eq(active != null, RedSite::getActive, active)
                .orderByDesc(RedSite::getCreatedAt);
        return ApiResponse.success(redSiteService.list(wrapper));
    }
}
