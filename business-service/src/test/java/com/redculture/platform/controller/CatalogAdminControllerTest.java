package com.redculture.platform.controller;

import com.redculture.platform.common.PageResult;
import com.redculture.platform.enums.EntityType;
import com.redculture.platform.enums.ResourceCategory;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.service.admin.CatalogAdminService;
import com.redculture.platform.service.admin.CatalogProjectionService;
import com.redculture.platform.vo.admin.CatalogMediaRequest;
import com.redculture.platform.vo.admin.CatalogEntityVO;
import com.redculture.platform.vo.admin.CatalogRelationVO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogAdminControllerTest {

    @Test
    void forwardsUnifiedCatalogFilters() {
        CatalogAdminService catalogService = mock(CatalogAdminService.class);
        CatalogProjectionService projectionService = mock(CatalogProjectionService.class);
        when(catalogService.page(EntityType.RESOURCE, ResourceCategory.LABOR_EDUCATION, 9L,
                ReviewStatus.PENDING, true, "劳动", 2L, 20L)).thenReturn(PageResult.of(List.of(), 0, 2, 20));

        var response = new CatalogAdminController(catalogService, projectionService).page(EntityType.RESOURCE,
                ResourceCategory.LABOR_EDUCATION, 9L, ReviewStatus.PENDING, true, "劳动", 2L, 20L);

        assertEquals(200, response.getCode());
        verify(catalogService).page(EntityType.RESOURCE, ResourceCategory.LABOR_EDUCATION, 9L,
                ReviewStatus.PENDING, true, "劳动", 2L, 20L);
    }

    @Test
    void uploadsMediaForExistingCatalogEntity() {
        CatalogAdminService catalogService = mock(CatalogAdminService.class);
        CatalogProjectionService projectionService = mock(CatalogProjectionService.class);
        CatalogMediaRequest media = new CatalogMediaRequest();
        media.setMediaId(7L);
        when(catalogService.uploadMedia(org.mockito.ArgumentMatchers.eq(EntityType.RESOURCE), org.mockito.ArgumentMatchers.eq(4L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(media);

        var response = new CatalogAdminController(catalogService, projectionService).uploadMedia(EntityType.RESOURCE, 4L,
                new MockMultipartFile("file", "cover.jpg", "image/jpeg", new byte[]{1}));

        assertEquals(200, response.getCode());
        assertEquals(7L, response.getData().getMediaId());
        verify(catalogService).uploadMedia(org.mockito.ArgumentMatchers.eq(EntityType.RESOURCE), org.mockito.ArgumentMatchers.eq(4L), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void returnsReadableCatalogRelationFields() {
        CatalogAdminService catalogService = mock(CatalogAdminService.class);
        CatalogProjectionService projectionService = mock(CatalogProjectionService.class);
        CatalogRelationVO relation = new CatalogRelationVO();
        relation.setSourceName("西柏坡纪念馆");
        relation.setRelationLabel("纪念");
        relation.setTargetName("革命英烈");
        when(catalogService.relations(EntityType.MEMORIAL, 1L)).thenReturn(List.of(relation));

        var response = new CatalogAdminController(catalogService, projectionService).relations(EntityType.MEMORIAL, 1L);

        assertEquals(200, response.getCode());
        assertEquals("西柏坡纪念馆", response.getData().getFirst().getSourceName());
        assertEquals("纪念", response.getData().getFirst().getRelationLabel());
        assertEquals("革命英烈", response.getData().getFirst().getTargetName());
        verify(catalogService).relations(EntityType.MEMORIAL, 1L);
    }
}
