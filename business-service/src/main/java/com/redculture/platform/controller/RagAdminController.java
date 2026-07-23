package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.service.rag.RagIndexService;
import com.redculture.platform.vo.ai.RagIndexReport;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/rag")
public class RagAdminController {

    private final RagIndexService ragIndexService;

    public RagAdminController(RagIndexService ragIndexService) {
        this.ragIndexService = ragIndexService;
    }

    @PostMapping("/reindex")
    public ApiResponse<RagIndexReport> reindex() {
        try {
            return ApiResponse.success("RAG vector index rebuilt", ragIndexService.rebuildAll());
        } catch (RuntimeException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }
}
