package com.redculture.platform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.entity.MemorialHall;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.service.MemorialHallService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/memorials")
public class MemorialHallController {

    private final MemorialHallService memorialHallService;

    public MemorialHallController(MemorialHallService memorialHallService) {
        this.memorialHallService = memorialHallService;
    }

    @GetMapping("/{id}")
    public ApiResponse<MemorialHall> getById(@PathVariable Long id) {
        return ApiResponse.success(memorialHallService.getById(id));
    }

    @GetMapping
    public ApiResponse<List<MemorialHall>> list(@RequestParam(required = false) Long regionId,
                                                @RequestParam(required = false) String memorialName,
                                                @RequestParam(required = false) ReviewStatus reviewStatus,
                                                @RequestParam(required = false) Boolean active) {
        LambdaQueryWrapper<MemorialHall> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(regionId != null, MemorialHall::getRegionId, regionId)
                .like(memorialName != null && !memorialName.isBlank(), MemorialHall::getMemorialName, memorialName)
                .eq(reviewStatus != null, MemorialHall::getReviewStatus, reviewStatus)
                .eq(active != null, MemorialHall::getActive, active)
                .orderByAsc(MemorialHall::getMemorialName);
        return ApiResponse.success(memorialHallService.list(wrapper));
    }
}
