package com.redculture.platform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.entity.RedStory;
import com.redculture.platform.enums.AgeGroup;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.service.RedStoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/stories")
public class RedStoryController {

    private final RedStoryService redStoryService;

    public RedStoryController(RedStoryService redStoryService) {
        this.redStoryService = redStoryService;
    }

    @GetMapping("/{id}")
    public ApiResponse<RedStory> getById(@PathVariable Long id) {
        return ApiResponse.success(redStoryService.getById(id));
    }

    @GetMapping
    public ApiResponse<List<RedStory>> list(@RequestParam(required = false) Long relatedRegionId,
                                            @RequestParam(required = false) String storyTitle,
                                            @RequestParam(required = false) AgeGroup ageGroup,
                                            @RequestParam(required = false) ReviewStatus reviewStatus,
                                            @RequestParam(required = false) Boolean active) {
        LambdaQueryWrapper<RedStory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(relatedRegionId != null, RedStory::getRelatedRegionId, relatedRegionId)
                .like(storyTitle != null && !storyTitle.isBlank(), RedStory::getStoryTitle, storyTitle)
                .eq(ageGroup != null, RedStory::getAgeGroup, ageGroup)
                .eq(reviewStatus != null, RedStory::getReviewStatus, reviewStatus)
                .eq(active != null, RedStory::getActive, active)
                .orderByDesc(RedStory::getCreatedAt);
        return ApiResponse.success(redStoryService.list(wrapper));
    }
}
