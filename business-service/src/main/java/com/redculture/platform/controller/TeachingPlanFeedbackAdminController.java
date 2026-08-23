package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.exception.TeachingPlanFeedbackException;
import com.redculture.platform.service.TeachingPlanFeedbackService;
import com.redculture.platform.vo.TeachingPlanFeedbackReportVO;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/admin/teaching-plan-feedback")
public class TeachingPlanFeedbackAdminController {

    private final TeachingPlanFeedbackService feedbackService;

    public TeachingPlanFeedbackAdminController(TeachingPlanFeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @GetMapping("/report")
    public ResponseEntity<ApiResponse<TeachingPlanFeedbackReportVO>> report(
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String theme,
            @RequestParam(required = false) String feedbackStatus,
            @RequestParam(required = false) Boolean adopted,
            @RequestParam(required = false) Boolean lowScoreOnly,
            @RequestParam(required = false) String reasonCode,
            @RequestParam(required = false) Long pageNum,
            @RequestParam(required = false) Long pageSize) {
        try {
            return ResponseEntity.ok(ApiResponse.success(feedbackService.report(
                    schoolId, startDate, endDate, theme, feedbackStatus, adopted,
                    lowScoreOnly, reasonCode, pageNum, pageSize)));
        } catch (TeachingPlanFeedbackException exception) {
            return ResponseEntity.status(exception.getStatus())
                    .body(ApiResponse.fail(exception.getStatus().value(), exception.getMessage()));
        }
    }
}
