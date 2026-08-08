package com.redculture.platform.service.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.entity.AdministrativeRegion;
import com.redculture.platform.entity.CatalogImportBatch;
import com.redculture.platform.entity.CatalogImportRow;
import com.redculture.platform.entity.CatalogProjectionTask;
import com.redculture.platform.enums.EntityType;
import com.redculture.platform.mapper.AdministrativeRegionMapper;
import com.redculture.platform.mapper.CatalogImportBatchMapper;
import com.redculture.platform.mapper.CatalogImportRowMapper;
import com.redculture.platform.mapper.CatalogProjectionTaskMapper;
import com.redculture.platform.mapper.ContentChunkMapper;
import com.redculture.platform.service.rag.RagIndexService;
import com.redculture.platform.vo.admin.CatalogEntityRequest;
import com.redculture.platform.vo.admin.CatalogEntityVO;
import com.redculture.platform.vo.admin.CatalogRelationVO;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogImportServiceTest {

    @Test
    void projectionConvertsBigDecimalCoordinatesToNeo4jSupportedDoubles() {
        assertEquals(114.953718D, CatalogProjectionService.neo4jNumber(new BigDecimal("114.953718")));
        assertEquals(38.027103D, CatalogProjectionService.neo4jNumber(new BigDecimal("38.027103")));
        assertEquals(null, CatalogProjectionService.neo4jNumber(null));
    }

    @Test
    void previewKeepsLegacyEntitySheetsAndClassifiesWorkbookDuplicates() throws Exception {
        CatalogImportBatchMapper batchMapper = mock(CatalogImportBatchMapper.class);
        CatalogImportRowMapper rowMapper = mock(CatalogImportRowMapper.class);
        CatalogAdminService catalogService = mock(CatalogAdminService.class);
        AdministrativeRegionMapper regionMapper = mock(AdministrativeRegionMapper.class);
        doAnswer(invocation -> {
            ((CatalogImportBatch) invocation.getArgument(0)).setBatchId(42L);
            return 1;
        }).when(batchMapper).insert(any(CatalogImportBatch.class));
        CatalogImportService service = service(batchMapper, rowMapper, catalogService, regionMapper);

        CatalogImportBatch batch = service.preview(legacyWorkbook("遗址", "编码", "名称",
                "S-001", "遗址甲", "S-001", "重复遗址", "", "缺少编码"));

        assertEquals(3, batch.getTotalRows());
        assertEquals(1, batch.getValidRows());
        assertEquals(1, batch.getDuplicateRows());
        assertEquals(1, batch.getInvalidRows());
        verify(rowMapper, org.mockito.Mockito.times(3)).insert(any(CatalogImportRow.class));
        verify(batchMapper).updateById(batch);
    }

    @Test
    void previewMapsReorderedResourceAliasesAndValidatesRegionCoordinatesAndDatabaseDuplicates() throws Exception {
        CatalogImportBatchMapper batchMapper = mock(CatalogImportBatchMapper.class);
        CatalogImportRowMapper rowMapper = mock(CatalogImportRowMapper.class);
        CatalogAdminService catalogService = mock(CatalogAdminService.class);
        AdministrativeRegionMapper regionMapper = mock(AdministrativeRegionMapper.class);
        doAnswer(invocation -> {
            ((CatalogImportBatch) invocation.getArgument(0)).setBatchId(43L);
            return 1;
        }).when(batchMapper).insert(any(CatalogImportBatch.class));
        AdministrativeRegion region = new AdministrativeRegion();
        region.setRegionId(9L);
        region.setRegionName("北京市");
        region.setAdcode("110000");
        when(regionMapper.selectList(any())).thenReturn(List.of(region));
        CatalogEntityVO existing = new CatalogEntityVO();
        existing.setEntityType("resource");
        existing.setEntityId(88L);
        existing.setCode("R-DB");
        when(catalogService.findByCode(eq(EntityType.RESOURCE), eq("R-DB"))).thenReturn(existing);
        ArgumentCaptor<CatalogImportRow> captor = ArgumentCaptor.forClass(CatalogImportRow.class);
        CatalogImportService service = service(batchMapper, rowMapper, catalogService, regionMapper);

        CatalogImportBatch batch = service.preview(resourceWorkbook(
                resourceRow("R-001", "红色基地", "红色文化", "北京市", "北京路1号", "116.4", "39.9", "简介", "教育价值", "政府门户", "小学"),
                resourceRow("R-001", "重复基地", "红色文化", "110000", "北京路2号", "116.4", "39.9", "简介", "教育价值", "政府门户", "小学"),
                resourceRow("R-DB", "数据库基地", "红色文化", "110000", "北京路3号", "116.4", "39.9", "简介", "教育价值", "政府门户", "小学"),
                resourceRow("R-BAD", "坐标错误", "红色文化", "110000", "北京路4号", "190", "39.9", "简介", "教育价值", "政府门户", "小学")));

        assertEquals(4, batch.getTotalRows());
        assertEquals(1, batch.getValidRows());
        assertEquals(2, batch.getDuplicateRows());
        assertEquals(1, batch.getInvalidRows());
        verify(rowMapper, org.mockito.Mockito.times(4)).insert(captor.capture());
        List<CatalogImportRow> rows = captor.getAllValues();
        assertEquals("VALID", rows.get(0).getValidationStatus());
        assertEquals("DUPLICATE", rows.get(1).getValidationStatus());
        assertTrue(rows.get(2).getValidationMessage().contains("数据库已有相同编码"));
        assertTrue(rows.get(3).getValidationMessage().contains("经度必须在"));
        assertTrue(rows.get(0).getPayloadJson().contains("\"resourceCategory\":\"red_culture\""));
    }

    @Test
    void previewAcceptsLegacyResourceHeadersAndReportsMissingCoreValuesPerRow() throws Exception {
        CatalogImportBatchMapper batchMapper = mock(CatalogImportBatchMapper.class);
        CatalogImportRowMapper rowMapper = mock(CatalogImportRowMapper.class);
        CatalogAdminService catalogService = mock(CatalogAdminService.class);
        AdministrativeRegionMapper regionMapper = mock(AdministrativeRegionMapper.class);
        doAnswer(invocation -> {
            ((CatalogImportBatch) invocation.getArgument(0)).setBatchId(46L);
            return 1;
        }).when(batchMapper).insert(any(CatalogImportBatch.class));
        AdministrativeRegion region = new AdministrativeRegion();
        region.setRegionId(9L);
        region.setAdcode("110000");
        when(regionMapper.selectList(any())).thenReturn(List.of(region));
        ArgumentCaptor<CatalogImportRow> captor = ArgumentCaptor.forClass(CatalogImportRow.class);

        CatalogImportBatch batch = service(batchMapper, rowMapper, catalogService, regionMapper).preview(legacyResourceWorkbook());

        assertEquals(2, batch.getTotalRows());
        assertEquals(1, batch.getValidRows());
        assertEquals(1, batch.getInvalidRows());
        verify(rowMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertTrue(captor.getAllValues().get(0).getPayloadJson().contains("教育价值"));
        assertTrue(captor.getAllValues().get(1).getValidationMessage().contains("资源类型不能为空"));
    }

    @Test
    void confirmImportsEntitiesAndWritesSameBatchRelationsWithoutProjecting() throws Exception {
        CatalogImportBatchMapper batchMapper = mock(CatalogImportBatchMapper.class);
        CatalogImportRowMapper rowMapper = mock(CatalogImportRowMapper.class);
        CatalogAdminService catalogService = mock(CatalogAdminService.class);
        AdministrativeRegionMapper regionMapper = mock(AdministrativeRegionMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        doAnswer(invocation -> {
            ((CatalogImportBatch) invocation.getArgument(0)).setBatchId(44L);
            return 1;
        }).when(batchMapper).insert(any(CatalogImportBatch.class));
        when(catalogService.create(any(CatalogEntityRequest.class))).thenAnswer(invocation -> {
            CatalogEntityRequest request = invocation.getArgument(0);
            CatalogEntityVO entity = new CatalogEntityVO();
            entity.setEntityType(request.getEntityType().getValue());
            entity.setEntityId(request.getEntityType() == EntityType.SITE ? 101L : 202L);
            return entity;
        });
        CatalogRelationVO relation = new CatalogRelationVO();
        relation.setRelationKind("site_event");
        relation.setRelationId(303L);
        when(catalogService.createImportedRelation(any())).thenReturn(relation);
        CatalogImportService service = service(batchMapper, rowMapper, catalogService, regionMapper);

        List<CatalogImportRow> insertedRows = new ArrayList<>();
        doAnswer(invocation -> {
            insertedRows.add(invocation.getArgument(0));
            return 1;
        }).when(rowMapper).insert(any(CatalogImportRow.class));
        service.preview(relationWorkbook());
        CatalogImportBatch batch = new CatalogImportBatch();
        batch.setBatchId(44L);
        batch.setStatus("PREVIEWED");
        batch.setValidRows(3);
        batch.setInvalidRows(0);
        batch.setDuplicateRows(0);
        when(batchMapper.selectById(44L)).thenReturn(batch);
        when(rowMapper.selectList(any())).thenReturn(insertedRows);

        CatalogImportBatch result = service.confirm(44L);

        assertEquals("CONFIRMED", result.getStatus());
        assertEquals("IMPORTED", insertedRows.get(0).getValidationStatus());
        assertEquals("IMPORTED", insertedRows.get(1).getValidationStatus());
        assertEquals("IMPORTED", insertedRows.get(2).getValidationStatus());
        assertTrue(insertedRows.get(2).getValidationMessage().contains("两端实体审核通过后投影"));
        verify(catalogService).createImportedRelation(any());
        verify(catalogService).submitForReview(EntityType.SITE, 101L);
        verify(catalogService).submitForReview(EntityType.EVENT, 202L);
        verify(rowMapper, org.mockito.Mockito.atLeast(3)).updateById(any(CatalogImportRow.class));
    }

    @Test
    void confirmRechecksDatabaseBeforeCreatingEntity() throws Exception {
        CatalogImportBatchMapper batchMapper = mock(CatalogImportBatchMapper.class);
        CatalogImportRowMapper rowMapper = mock(CatalogImportRowMapper.class);
        CatalogAdminService catalogService = mock(CatalogAdminService.class);
        AdministrativeRegionMapper regionMapper = mock(AdministrativeRegionMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        CatalogImportBatch batch = new CatalogImportBatch();
        batch.setBatchId(45L);
        batch.setStatus("PREVIEWED");
        batch.setValidRows(1);
        CatalogImportRow row = new CatalogImportRow();
        row.setRowId(1L);
        row.setBatchId(45L);
        row.setEntityType("site");
        row.setValidationStatus("VALID");
        CatalogEntityRequest request = new CatalogEntityRequest();
        request.setEntityType(EntityType.SITE);
        request.setCode("S-RACE");
        request.setName("并发资源");
        row.setPayloadJson(objectMapper.writeValueAsString(request));
        CatalogEntityVO existing = new CatalogEntityVO();
        existing.setEntityId(77L);
        when(batchMapper.selectById(45L)).thenReturn(batch);
        when(rowMapper.selectList(any())).thenReturn(List.of(row));
        when(catalogService.findByCode(EntityType.SITE, "S-RACE")).thenReturn(existing);

        new CatalogImportService(batchMapper, rowMapper, catalogService, regionMapper, objectMapper).confirm(45L);

        assertEquals("DUPLICATE", row.getValidationStatus());
        assertTrue(row.getValidationMessage().contains("确认导入时发现"));
        org.mockito.Mockito.verify(catalogService, org.mockito.Mockito.never()).create(any());
    }

    @Test
    void relationProjectionWaitsForBothApprovedEndpoints() {
        CatalogProjectionTaskMapper taskMapper = mock(CatalogProjectionTaskMapper.class);
        ContentChunkMapper chunkMapper = mock(ContentChunkMapper.class);
        RagIndexService ragIndexService = mock(RagIndexService.class);
        Neo4jClient neo4jClient = mock(Neo4jClient.class);
        CatalogAdminService catalogService = mock(CatalogAdminService.class);
        CatalogProjectionTask task = new CatalogProjectionTask();
        doAnswer(invocation -> {
            ((CatalogProjectionTask) invocation.getArgument(0)).setTaskId(501L);
            return 1;
        }).when(taskMapper).insert(any(CatalogProjectionTask.class));
        when(taskMapper.selectById(501L)).thenReturn(task);
        CatalogRelationVO relation = new CatalogRelationVO();
        relation.setRelationKind("site_event");
        relation.setRelationId(31L);
        relation.setSourceType("site");
        relation.setSourceId(10L);
        relation.setTargetType("event");
        relation.setTargetId(20L);
        relation.setRelationType("OCCURRED_AT");
        CatalogProjectionService service = new CatalogProjectionService(taskMapper, chunkMapper, ragIndexService, neo4jClient, catalogService);

        when(catalogService.isPublished(EntityType.SITE, 10L)).thenReturn(true);
        when(catalogService.isPublished(EntityType.EVENT, 20L)).thenReturn(false);
        assertEquals(null, service.projectRelation(relation));
        verify(taskMapper, never()).insert(any(CatalogProjectionTask.class));

        when(catalogService.isPublished(EntityType.EVENT, 20L)).thenReturn(true);
        when(neo4jClient.query(anyString())).thenThrow(new IllegalStateException("neo4j unavailable"));
        assertTrue(service.projectRelation(relation) != null);
        verify(taskMapper).insert(any(CatalogProjectionTask.class));
    }

    private CatalogImportService service(CatalogImportBatchMapper batchMapper, CatalogImportRowMapper rowMapper,
                                         CatalogAdminService catalogService, AdministrativeRegionMapper regionMapper) {
        return new CatalogImportService(batchMapper, rowMapper, catalogService, regionMapper, new ObjectMapper());
    }

    private MockMultipartFile legacyWorkbook(String sheetName, String headerCode, String headerName, String... values) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(sheetName);
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue(headerCode);
            header.createCell(1).setCellValue(headerName);
            for (int index = 0; index < values.length; index += 2) {
                var row = sheet.createRow(index / 2 + 1);
                row.createCell(0).setCellValue(values[index]);
                row.createCell(1).setCellValue(values[index + 1]);
            }
            workbook.write(output);
            return new MockMultipartFile("file", "catalog.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }

    private MockMultipartFile resourceWorkbook(Map<String, String>... rows) throws Exception {
        String[] headers = {"数据来源", "纬度", "编码", "教育价值", "资源类型", "资源名称", "行政区域", "地址", "经度", "简介", "适合学段"};
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("资源");
            var header = sheet.createRow(0);
            for (int index = 0; index < headers.length; index++) header.createCell(index).setCellValue(headers[index]);
            for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
                var row = sheet.createRow(rowIndex + 1);
                for (int index = 0; index < headers.length; index++) row.createCell(index).setCellValue(rows[rowIndex].getOrDefault(headers[index], ""));
            }
            workbook.write(output);
            return new MockMultipartFile("file", "catalog.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }

    private MockMultipartFile legacyResourceWorkbook() throws Exception {
        String[] headers = {"来源URL", "详情", "经度", "编码", "名称", "区域ID", "纬度", "简介", "地址", "资源类型", "适合学段"};
        String[] valid = {"政府门户", "教育价值", "116.4", "R-OLD", "旧模板资源", "110000", "39.9", "简介", "北京路", "红色文化", "小学"};
        String[] invalid = {"政府门户", "教育价值", "116.4", "R-MISSING", "缺字段资源", "110000", "39.9", "简介", "北京路", "", ""};
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("资源");
            var header = sheet.createRow(0);
            for (int index = 0; index < headers.length; index++) header.createCell(index).setCellValue(headers[index]);
            var validRow = sheet.createRow(1);
            var invalidRow = sheet.createRow(2);
            for (int index = 0; index < headers.length; index++) {
                validRow.createCell(index).setCellValue(valid[index]);
                invalidRow.createCell(index).setCellValue(invalid[index]);
            }
            workbook.write(output);
            return new MockMultipartFile("file", "catalog.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }

    private Map<String, String> resourceRow(String code, String name, String category, String region, String address,
                                            String longitude, String latitude, String summary, String education,
                                            String source, String grade) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("编码", code);
        row.put("资源名称", name);
        row.put("资源类型", category);
        row.put("行政区域", region);
        row.put("地址", address);
        row.put("经度", longitude);
        row.put("纬度", latitude);
        row.put("简介", summary);
        row.put("教育价值", education);
        row.put("数据来源", source);
        row.put("适合学段", grade);
        return row;
    }

    private MockMultipartFile relationWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var site = workbook.createSheet("遗址");
            site.createRow(0).createCell(0).setCellValue("编码");
            site.getRow(0).createCell(1).setCellValue("名称");
            site.createRow(1).createCell(0).setCellValue("S-001");
            site.getRow(1).createCell(1).setCellValue("遗址甲");
            var event = workbook.createSheet("事件");
            event.createRow(0).createCell(0).setCellValue("名称");
            event.getRow(0).createCell(1).setCellValue("编码");
            event.createRow(1).createCell(0).setCellValue("事件甲");
            event.getRow(1).createCell(1).setCellValue("E-001");
            var relation = workbook.createSheet("关系");
            String[] headers = {"目标实体编码", "源实体类型", "备注", "关系类型", "源实体编码", "目标实体类型"};
            var relationHeader = relation.createRow(0);
            for (int index = 0; index < headers.length; index++) relationHeader.createCell(index).setCellValue(headers[index]);
            String[] values = {"E-001", "遗址", "发生地", "OCCURRED_AT", "S-001", "事件"};
            var relationRow = relation.createRow(1);
            for (int index = 0; index < values.length; index++) relationRow.createCell(index).setCellValue(values[index]);
            workbook.write(output);
            return new MockMultipartFile("file", "catalog.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }
}
