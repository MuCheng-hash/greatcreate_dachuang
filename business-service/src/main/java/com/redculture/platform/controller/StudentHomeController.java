package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.service.*;
import com.redculture.platform.vo.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/student")
public class StudentHomeController {
    private final StudentHomeService homeService;
    private final StudentResourceBrowseService browseService;
    public StudentHomeController(StudentHomeService homeService, StudentResourceBrowseService browseService) { this.homeService = homeService; this.browseService = browseService; }

    @GetMapping("/home")
    public ApiResponse<StudentHomeVO> home(HttpServletRequest request) { try { return ApiResponse.success(homeService.home(AuthContext.requireUser(request))); } catch (IllegalArgumentException e) { return ApiResponse.fail(e.getMessage()); } }

    @PostMapping("/resources/{resourceId}/view")
    public ApiResponse<Void> view(@PathVariable Long resourceId, HttpServletRequest request) { try { browseService.record(resourceId, AuthContext.requireUser(request)); return ApiResponse.success(null); } catch (IllegalArgumentException e) { return ApiResponse.fail(e.getMessage()); } }

    @GetMapping("/resources/recent")
    public ApiResponse<java.util.List<StudentRecentResourceVO>> recent(@RequestParam(required = false) Integer limit, HttpServletRequest request) { try { return ApiResponse.success(browseService.recent(AuthContext.requireUser(request), limit)); } catch (IllegalArgumentException e) { return ApiResponse.fail(e.getMessage()); } }
    @GetMapping("/resources/history")
    public ApiResponse<com.redculture.platform.common.PageResult<StudentRecentResourceVO>> history(@RequestParam(required = false) Long pageNum, @RequestParam(required = false) Long pageSize, HttpServletRequest request) { try { return ApiResponse.success(browseService.history(AuthContext.requireUser(request), pageNum, pageSize)); } catch (IllegalArgumentException e) { return ApiResponse.fail(e.getMessage()); } }
    @DeleteMapping("/resources/history/{resourceId}")
    public ApiResponse<Void> remove(@PathVariable Long resourceId, HttpServletRequest request) { try { browseService.remove(resourceId, AuthContext.requireUser(request)); return ApiResponse.success(null); } catch (IllegalArgumentException e) { return ApiResponse.fail(e.getMessage()); } }
    @DeleteMapping("/resources/history")
    public ApiResponse<Void> clear(HttpServletRequest request) { try { browseService.clear(AuthContext.requireUser(request)); return ApiResponse.success(null); } catch (IllegalArgumentException e) { return ApiResponse.fail(e.getMessage()); } }
}
