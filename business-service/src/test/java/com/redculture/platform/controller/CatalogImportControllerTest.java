package com.redculture.platform.controller;

import com.redculture.platform.service.admin.CatalogImportService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class CatalogImportControllerTest {

    @Test
    void templateContainsAllCatalogSheets() throws Exception {
        CatalogImportController controller = new CatalogImportController(mock(CatalogImportService.class));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.template(response);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertEquals(List.of("资源", "遗址", "纪念馆", "人物", "事件", "故事", "关系"),
                    IntStream.range(0, workbook.getNumberOfSheets()).mapToObj(workbook::getSheetName).toList());
            assertEquals("编码", workbook.getSheet("资源").getRow(0).getCell(0).getStringCellValue());
            assertEquals("资源名称", workbook.getSheet("资源").getRow(0).getCell(1).getStringCellValue());
            assertEquals("资源类型", workbook.getSheet("资源").getRow(0).getCell(2).getStringCellValue());
            assertEquals("行政区域", workbook.getSheet("资源").getRow(0).getCell(3).getStringCellValue());
            assertEquals("教育价值", workbook.getSheet("资源").getRow(0).getCell(8).getStringCellValue());
            assertEquals("数据来源", workbook.getSheet("资源").getRow(0).getCell(9).getStringCellValue());
            assertEquals("适合学段", workbook.getSheet("资源").getRow(0).getCell(10).getStringCellValue());
            assertEquals("源实体类型", workbook.getSheet("关系").getRow(0).getCell(0).getStringCellValue());
        }
    }
}
