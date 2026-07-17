package com.redculture.platform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.entity.HistoricalEvent;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.service.HistoricalEventService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/events")
public class HistoricalEventController {

    private final HistoricalEventService historicalEventService;

    public HistoricalEventController(HistoricalEventService historicalEventService) {
        this.historicalEventService = historicalEventService;
    }

    @GetMapping("/{id}")
    public ApiResponse<HistoricalEvent> getById(@PathVariable Long id) {
        return ApiResponse.success(historicalEventService.getById(id));
    }

    @GetMapping
    public ApiResponse<List<HistoricalEvent>> list(@RequestParam(required = false) Long primaryRegionId,
                                                   @RequestParam(required = false) String eventName,
                                                   @RequestParam(required = false) ReviewStatus reviewStatus,
                                                   @RequestParam(required = false) Boolean active) {
        LambdaQueryWrapper<HistoricalEvent> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(primaryRegionId != null, HistoricalEvent::getPrimaryRegionId, primaryRegionId)
                .like(eventName != null && !eventName.isBlank(), HistoricalEvent::getEventName, eventName)
                .eq(reviewStatus != null, HistoricalEvent::getReviewStatus, reviewStatus)
                .eq(active != null, HistoricalEvent::getActive, active)
                .orderByDesc(HistoricalEvent::getStartDate)
                .orderByAsc(HistoricalEvent::getEventName);
        return ApiResponse.success(historicalEventService.list(wrapper));
    }
}
