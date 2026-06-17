package com.redculture.platform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.entity.AdministrativeRegion;
import com.redculture.platform.enums.RegionLevel;
import com.redculture.platform.service.AdministrativeRegionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/regions")
public class AdministrativeRegionController {

    private final AdministrativeRegionService administrativeRegionService;

    public AdministrativeRegionController(AdministrativeRegionService administrativeRegionService) {
        this.administrativeRegionService = administrativeRegionService;
    }

    @GetMapping("/{id}")
    public ApiResponse<AdministrativeRegion> getById(@PathVariable Long id) {
        return ApiResponse.success(administrativeRegionService.getById(id));
    }

    @GetMapping
    public ApiResponse<List<AdministrativeRegion>> list(@RequestParam(required = false) Long parentRegionId,
                                                        @RequestParam(required = false) String regionName,
                                                        @RequestParam(required = false) RegionLevel regionLevel) {
        LambdaQueryWrapper<AdministrativeRegion> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(parentRegionId != null, AdministrativeRegion::getParentRegionId, parentRegionId)
                .like(regionName != null && !regionName.isBlank(), AdministrativeRegion::getRegionName, regionName)
                .eq(regionLevel != null, AdministrativeRegion::getRegionLevel, regionLevel)
                .orderByAsc(AdministrativeRegion::getRegionLevel)
                .orderByAsc(AdministrativeRegion::getRegionName);
        return ApiResponse.success(administrativeRegionService.list(wrapper));
    }
}
