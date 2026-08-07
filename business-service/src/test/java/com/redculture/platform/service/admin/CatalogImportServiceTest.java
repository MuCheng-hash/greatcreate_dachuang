package com.redculture.platform.service.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.entity.CatalogImportBatch;
import com.redculture.platform.entity.CatalogImportRow;
import com.redculture.platform.enums.EntityType;
import com.redculture.platform.mapper.CatalogImportBatchMapper;
import com.redculture.platform.mapper.CatalogImportRowMapper;
import com.redculture.platform.vo.admin.CatalogEntityRequest;
import com.redculture.platform.vo.admin.CatalogEntityVO;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogImportServiceTest {

    @Test
    void previewClassifiesValidInvalidAndDuplicateRows() throws Exception {
        CatalogImportBatchMapper batchMapper = mock(CatalogImportBatchMapper.class);
        CatalogImportRowMapper rowMapper = mock(CatalogImportRowMapper.class);
        CatalogAdminService catalogService = mock(CatalogAdminService.class);
        doAnswer(invocation -> {
            ((CatalogImportBatch) invocation.getArgument(0)).setBatchId(42L);
            return 1;
        }).when(batchMapper).insert(any(CatalogImportBatch.class));
        CatalogImportService service = new CatalogImportService(batchMapper, rowMapper, catalogService, new ObjectMapper());

        CatalogImportBatch batch = service.preview(workbook("R-001", "资源甲", "R-001", "重复资源", "", "缺少编码"));

        assertEquals(3, batch.getTotalRows());
        assertEquals(1, batch.getValidRows());
        assertEquals(1, batch.getDuplicateRows());
        assertEquals(1, batch.getInvalidRows());
        verify(rowMapper, org.mockito.Mockito.times(3)).insert(any(CatalogImportRow.class));
        verify(batchMapper).updateById(batch);
    }

    @Test
    void confirmImportsOnlyValidEntityRowsAsPendingReview() throws Exception {
        CatalogImportBatchMapper batchMapper = mock(CatalogImportBatchMapper.class);
        CatalogImportRowMapper rowMapper = mock(CatalogImportRowMapper.class);
        CatalogAdminService catalogService = mock(CatalogAdminService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        CatalogEntityRequest request = new CatalogEntityRequest();
        request.setEntityType(EntityType.SITE); request.setCode("S-001"); request.setName("测试遗址");
        CatalogImportBatch batch = new CatalogImportBatch(); batch.setBatchId(7L); batch.setStatus("PREVIEWED");
        CatalogImportRow valid = new CatalogImportRow(); valid.setRowId(1L); valid.setBatchId(7L); valid.setEntityType("site"); valid.setValidationStatus("VALID"); valid.setPayloadJson(objectMapper.writeValueAsString(request));
        CatalogImportRow invalid = new CatalogImportRow(); invalid.setRowId(2L); invalid.setBatchId(7L); invalid.setEntityType("site"); invalid.setValidationStatus("INVALID");
        CatalogEntityVO created = new CatalogEntityVO(); created.setEntityId(99L); created.setEntityType("site");
        when(batchMapper.selectById(7L)).thenReturn(batch);
        when(rowMapper.selectList(any())).thenReturn(List.of(valid, invalid));
        when(catalogService.create(any(CatalogEntityRequest.class))).thenReturn(created);

        CatalogImportBatch result = new CatalogImportService(batchMapper, rowMapper, catalogService, objectMapper).confirm(7L);

        assertEquals("CONFIRMED", result.getStatus());
        assertEquals("IMPORTED", valid.getValidationStatus());
        assertEquals(99L, valid.getImportedEntityId());
        assertTrue(valid.getValidationMessage().contains("等待审核"));
        verify(catalogService).submitForReview(EntityType.SITE, 99L);
        verify(rowMapper).updateById(valid);
        verify(batchMapper).updateById(batch);
    }

    private MockMultipartFile workbook(String... values) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("资源");
            sheet.createRow(0).createCell(0).setCellValue("编码");
            for (int index = 0; index < values.length; index += 2) {
                var row = sheet.createRow(index / 2 + 1);
                row.createCell(0).setCellValue(values[index]);
                row.createCell(1).setCellValue(values[index + 1]);
            }
            workbook.write(output);
            return new MockMultipartFile("file", "catalog.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }
}
