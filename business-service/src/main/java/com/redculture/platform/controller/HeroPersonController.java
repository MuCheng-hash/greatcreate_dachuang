package com.redculture.platform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.entity.HeroPerson;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.service.HeroPersonService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/heroes")
public class HeroPersonController {

    private final HeroPersonService heroPersonService;

    public HeroPersonController(HeroPersonService heroPersonService) {
        this.heroPersonService = heroPersonService;
    }

    @GetMapping("/{id}")
    public ApiResponse<HeroPerson> getById(@PathVariable Long id) {
        return ApiResponse.success(heroPersonService.getById(id));
    }

    @GetMapping
    public ApiResponse<List<HeroPerson>> list(@RequestParam(required = false) String heroName,
                                              @RequestParam(required = false) Long nativePlaceRegionId,
                                              @RequestParam(required = false) ReviewStatus reviewStatus,
                                              @RequestParam(required = false) Boolean active) {
        LambdaQueryWrapper<HeroPerson> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(heroName != null && !heroName.isBlank(), HeroPerson::getHeroName, heroName)
                .eq(nativePlaceRegionId != null, HeroPerson::getNativePlaceRegionId, nativePlaceRegionId)
                .eq(reviewStatus != null, HeroPerson::getReviewStatus, reviewStatus)
                .eq(active != null, HeroPerson::getActive, active)
                .orderByAsc(HeroPerson::getHeroName);
        return ApiResponse.success(heroPersonService.list(wrapper));
    }
}
