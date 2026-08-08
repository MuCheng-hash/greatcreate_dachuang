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
//行政区划查询，例如按父级查询省、市、区县、乡镇。
public class AdministrativeRegionController {

    private final AdministrativeRegionService administrativeRegionService;

    public AdministrativeRegionController(AdministrativeRegionService administrativeRegionService) {
        this.administrativeRegionService = administrativeRegionService;
    }

    //获取id查询行政区
    @GetMapping("/{id}")
    public ApiResponse<AdministrativeRegion> getById(@PathVariable Long id) {
        return ApiResponse.success(administrativeRegionService.getById(id));
    }

    //查询行政区列表
    /*
    parentRegionId：父级区域 ID，例如查询某市下辖的区县。
regionName：按名称模糊搜索，例如输入“庄”可匹配“石家庄市”。
regionLevel：行政级别，具体可选值由 RegionLevel 枚举定义，例如省、市、区县、乡镇等。
     */
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
