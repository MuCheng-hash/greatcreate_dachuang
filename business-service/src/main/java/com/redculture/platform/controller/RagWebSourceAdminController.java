package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.service.admin.RagWebSourceService;
import com.redculture.platform.vo.admin.RagWebSourceRequest;
import com.redculture.platform.vo.admin.RagWebSourceVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/rag/web-sources")
public class RagWebSourceAdminController {

    private final RagWebSourceService service;

    public RagWebSourceAdminController(RagWebSourceService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<RagWebSourceVO>> list() {
        return ApiResponse.success(service.list());
    }

    @PostMapping
    public ApiResponse<RagWebSourceVO> create(@RequestBody RagWebSourceRequest request) {
        try {
            return ApiResponse.success("web source created", service.create(request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PutMapping("/{sourceId}")
    public ApiResponse<RagWebSourceVO> update(@PathVariable Long sourceId, @RequestBody RagWebSourceRequest request) {
        try {
            return ApiResponse.success("web source updated", service.update(sourceId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }
}
