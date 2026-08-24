package com.redculture.platform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.entity.AdministrativeRegion;
import com.redculture.platform.entity.School;
import com.redculture.platform.mapper.AdministrativeRegionMapper;
import com.redculture.platform.mapper.SchoolMapper;
import com.redculture.platform.vo.PublicSchoolVO;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/public/schools")
public class PublicSchoolController {
    private final SchoolMapper schoolMapper;
    private final AdministrativeRegionMapper regionMapper;

    public PublicSchoolController(SchoolMapper schoolMapper, AdministrativeRegionMapper regionMapper) {
        this.schoolMapper = schoolMapper;
        this.regionMapper = regionMapper;
    }

    @GetMapping
    public ApiResponse<List<PublicSchoolVO>> list(@RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<School> query = new LambdaQueryWrapper<School>()
                .eq(School::getActive, true)
                .eq(School::getReviewStatus, "approved")
                .orderByAsc(School::getSchoolName)
                .last("LIMIT 100");
        if (StringUtils.hasText(keyword)) query.like(School::getSchoolName, keyword.trim());
        List<School> schools = schoolMapper.selectList(query);
        List<Long> regionIds = schools.stream().map(School::getTownshipRegionId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, AdministrativeRegion> regions = regionIds.isEmpty() ? Map.of() : regionMapper.selectBatchIds(regionIds)
                .stream().collect(Collectors.toMap(AdministrativeRegion::getRegionId, Function.identity()));
        List<PublicSchoolVO> result = schools.stream().map(school -> {
            PublicSchoolVO item = new PublicSchoolVO();
            item.setSchoolId(school.getSchoolId());
            item.setSchoolName(school.getSchoolName());
            item.setSchoolType(school.getSchoolType());
            AdministrativeRegion region = regions.get(school.getTownshipRegionId());
            item.setRegionName(region == null ? null : region.getRegionName());
            return item;
        }).toList();
        return ApiResponse.success(result);
    }
}
