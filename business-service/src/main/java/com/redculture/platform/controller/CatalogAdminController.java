package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.entity.CatalogProjectionTask;
import com.redculture.platform.enums.EntityType;
import com.redculture.platform.service.admin.CatalogAdminService;
import com.redculture.platform.service.admin.CatalogProjectionService;
import com.redculture.platform.vo.admin.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/catalog")
public class CatalogAdminController {
    private final CatalogAdminService catalogService;
    private final CatalogProjectionService projectionService;
    public CatalogAdminController(CatalogAdminService catalogService, CatalogProjectionService projectionService) { this.catalogService = catalogService; this.projectionService = projectionService; }
    @GetMapping("/entities")
    public ApiResponse<PageResult<CatalogEntityVO>> page(@RequestParam(required = false) EntityType entityType, @RequestParam(required = false) String keyword, @RequestParam(required = false) Long pageNum, @RequestParam(required = false) Long pageSize) { return ApiResponse.success(catalogService.page(entityType, keyword, pageNum, pageSize)); }
    @GetMapping("/entities/{entityType}/{entityId}")
    public ApiResponse<CatalogEntityVO> detail(@PathVariable EntityType entityType,@PathVariable Long entityId){CatalogEntityVO item=catalogService.detail(entityType,entityId);return item==null?ApiResponse.fail("catalog entity not found"):ApiResponse.success(item);}
    @PostMapping("/entities")
    public ApiResponse<CatalogEntityVO> create(@RequestBody CatalogEntityRequest request){try{return ApiResponse.success("catalog entity created",catalogService.create(request));}catch(IllegalArgumentException ex){return ApiResponse.fail(ex.getMessage());}}
    @PutMapping("/entities/{entityType}/{entityId}")
    public ApiResponse<CatalogEntityVO> update(@PathVariable EntityType entityType,@PathVariable Long entityId,@RequestBody CatalogEntityRequest request){try{return ApiResponse.success("catalog entity updated",catalogService.update(entityType,entityId,request));}catch(IllegalArgumentException ex){return ApiResponse.fail(ex.getMessage());}}
    @PostMapping("/entities/{entityType}/{entityId}/approve")
    public ApiResponse<CatalogEntityVO> approve(@PathVariable EntityType entityType,@PathVariable Long entityId){try{CatalogEntityVO item=catalogService.approve(entityType,entityId);projectionService.projectEntity(item);return ApiResponse.success("catalog entity approved",item);}catch(IllegalArgumentException ex){return ApiResponse.fail(ex.getMessage());}}
    @DeleteMapping("/entities/{entityType}/{entityId}")
    public ApiResponse<CatalogEntityVO> deactivate(@PathVariable EntityType entityType,@PathVariable Long entityId){try{CatalogEntityVO item=catalogService.deactivate(entityType,entityId);projectionService.projectEntity(item);return ApiResponse.success("catalog entity deactivated",item);}catch(IllegalArgumentException ex){return ApiResponse.fail(ex.getMessage());}}
    @PostMapping("/relations")
    public ApiResponse<CatalogRelationVO> relation(@RequestBody CatalogRelationRequest request){try{CatalogRelationVO item=catalogService.createRelation(request);projectionService.projectRelation(item);return ApiResponse.success("catalog relation created",item);}catch(IllegalArgumentException ex){return ApiResponse.fail(ex.getMessage());}}
    @PostMapping("/projection-tasks/{taskId}/retry")
    public ApiResponse<CatalogProjectionTask> retry(@PathVariable Long taskId){try{return ApiResponse.success("projection task queued",projectionService.retry(taskId));}catch(IllegalArgumentException ex){return ApiResponse.fail(ex.getMessage());}}
    @GetMapping("/projection-tasks")
    public ApiResponse<List<CatalogProjectionTask>> projectionTasks(){return ApiResponse.success(projectionService.tasks());}
}
