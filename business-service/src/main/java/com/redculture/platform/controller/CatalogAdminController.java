package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.entity.CatalogProjectionTask;
import com.redculture.platform.enums.EntityType;
import com.redculture.platform.enums.ResourceCategory;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.service.admin.CatalogAdminService;
import com.redculture.platform.service.admin.CatalogProjectionService;
import com.redculture.platform.vo.admin.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/admin/catalog")
public class CatalogAdminController {
    private final CatalogAdminService catalogService;
    private final CatalogProjectionService projectionService;
    public CatalogAdminController(CatalogAdminService catalogService, CatalogProjectionService projectionService) { this.catalogService = catalogService; this.projectionService = projectionService; }
    @GetMapping("/entities")
    public ApiResponse<PageResult<CatalogEntityVO>> page(@RequestParam(required = false) EntityType entityType, @RequestParam(required = false) ResourceCategory resourceCategory, @RequestParam(required = false) Long regionId, @RequestParam(required = false) ReviewStatus reviewStatus, @RequestParam(required = false) Boolean active, @RequestParam(required = false) String keyword, @RequestParam(required = false) Long pageNum, @RequestParam(required = false) Long pageSize) { return ApiResponse.success(catalogService.page(entityType, resourceCategory, regionId, reviewStatus, active, keyword, pageNum, pageSize)); }
    @GetMapping("/entities/{entityType}/{entityId}")
    public ApiResponse<CatalogEntityVO> detail(@PathVariable EntityType entityType,@PathVariable Long entityId){CatalogEntityVO item=catalogService.detail(entityType,entityId);return item==null?ApiResponse.fail("catalog entity not found"):ApiResponse.success(item);}
    @PostMapping("/entities")
    public ApiResponse<CatalogEntityVO> create(@RequestBody CatalogEntityRequest request){try{return ApiResponse.success("catalog entity created",catalogService.create(request));}catch(IllegalArgumentException ex){return ApiResponse.fail(ex.getMessage());}}
    @PutMapping("/entities/{entityType}/{entityId}")
    public ApiResponse<CatalogEntityVO> update(@PathVariable EntityType entityType,@PathVariable Long entityId,@RequestBody CatalogEntityRequest request){try{CatalogEntityVO item=catalogService.update(entityType,entityId,request);projectionService.projectEntity(item);return ApiResponse.success("catalog entity updated",item);}catch(IllegalArgumentException ex){return ApiResponse.fail(ex.getMessage());}}
    @PostMapping("/entities/{entityType}/{entityId}/submit-review")
    public ApiResponse<CatalogEntityVO> submitForReview(@PathVariable EntityType entityType,@PathVariable Long entityId){try{CatalogEntityVO item=catalogService.submitForReview(entityType,entityId);projectionService.projectEntity(item);return ApiResponse.success("catalog entity submitted",item);}catch(IllegalArgumentException ex){return ApiResponse.fail(ex.getMessage());}}
    @PostMapping("/entities/{entityType}/{entityId}/approve")
    public ApiResponse<CatalogEntityVO> approve(@PathVariable EntityType entityType,@PathVariable Long entityId){try{CatalogEntityVO item=catalogService.approve(entityType,entityId);projectEntityAndRelations(entityType, entityId, item);return ApiResponse.success("catalog entity approved",item);}catch(IllegalArgumentException ex){return ApiResponse.fail(ex.getMessage());}}
    @DeleteMapping("/entities/{entityType}/{entityId}")
    public ApiResponse<CatalogEntityVO> deactivate(@PathVariable EntityType entityType,@PathVariable Long entityId){try{CatalogEntityVO item=catalogService.deactivate(entityType,entityId);projectionService.projectEntity(item);return ApiResponse.success("catalog entity deactivated",item);}catch(IllegalArgumentException ex){return ApiResponse.fail(ex.getMessage());}}
    @PostMapping("/entities/{entityType}/{entityId}/media")
    public ApiResponse<CatalogMediaRequest> uploadMedia(@PathVariable EntityType entityType, @PathVariable Long entityId, @RequestParam("file") MultipartFile file){try{return ApiResponse.success("catalog media uploaded",catalogService.uploadMedia(entityType,entityId,file));}catch(IllegalArgumentException | IllegalStateException ex){return ApiResponse.fail(ex.getMessage());}}
    @DeleteMapping("/entities/{entityType}/{entityId}/media/{mediaId}")
    public ApiResponse<Void> deleteMedia(@PathVariable EntityType entityType,@PathVariable Long entityId,@PathVariable Long mediaId){try{catalogService.deleteMedia(entityType,entityId,mediaId);return ApiResponse.success("catalog media deleted",null);}catch(IllegalArgumentException | IllegalStateException ex){return ApiResponse.fail(ex.getMessage());}}
    @PostMapping("/relations")
    public ApiResponse<CatalogRelationVO> relation(@RequestBody CatalogRelationRequest request){try{CatalogRelationVO item=catalogService.createRelation(request);projectionService.projectRelation(item);return ApiResponse.success("catalog relation created",item);}catch(IllegalArgumentException ex){return ApiResponse.fail(ex.getMessage());}}
    @GetMapping("/entities/{entityType}/{entityId}/relations")
    public ApiResponse<List<CatalogRelationVO>> relations(@PathVariable EntityType entityType,@PathVariable Long entityId){try{return ApiResponse.success(catalogService.relations(entityType,entityId));}catch(IllegalArgumentException ex){return ApiResponse.fail(ex.getMessage());}}
    @DeleteMapping("/relations/{kind}/{relationId}")
    public ApiResponse<CatalogRelationVO> deleteRelation(@PathVariable String kind,@PathVariable Long relationId){try{CatalogRelationVO relation=catalogService.relation(kind,relationId);if(relation==null)return ApiResponse.fail("catalog relation not found");projectionService.removeRelation(relation);return ApiResponse.success("catalog relation deleted",catalogService.deleteRelation(kind,relationId));}catch(IllegalArgumentException ex){return ApiResponse.fail(ex.getMessage());}}
    @GetMapping("/relation-options")
    public ApiResponse<List<CatalogRelationOptionVO>> relationOptions(){return ApiResponse.success(catalogService.relationOptions());}
    @PostMapping("/projection-tasks/{taskId}/retry")
    public ApiResponse<CatalogProjectionTask> retry(@PathVariable Long taskId){try{return ApiResponse.success("projection task queued",projectionService.retry(taskId));}catch(IllegalArgumentException ex){return ApiResponse.fail(ex.getMessage());}}
    @GetMapping("/projection-tasks")
    public ApiResponse<List<CatalogProjectionTask>> projectionTasks(){return ApiResponse.success(projectionService.tasks());}

    private void projectEntityAndRelations(EntityType entityType, Long entityId, CatalogEntityVO item) {
        projectionService.projectEntity(item);
        catalogService.relations(entityType, entityId).forEach(projectionService::projectRelation);
    }
}
