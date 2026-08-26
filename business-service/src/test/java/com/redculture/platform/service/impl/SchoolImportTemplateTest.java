package com.redculture.platform.service.impl;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SchoolImportTemplateTest {

    @Test
    void buildsChineseTemplateWithOneCommentPerHeaderCell() throws Exception {
        byte[] content = new SchoolServiceImpl(null).buildImportTemplate();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            var header = workbook.getSheetAt(0).getRow(0);
            assertEquals("学校名称", header.getCell(0).getStringCellValue());
            assertEquals("负责人姓名", header.getCell(12).getStringCellValue());
            for (int column = 0; column <= 12; column++) {
                assertNotNull(header.getCell(column).getCellComment());
            }
            assertEquals("示例数据：导入前请删除下一行的“平山县西柏坡希望小学”示例信息。", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
            assertEquals("平山县西柏坡希望小学", workbook.getSheetAt(0).getRow(2).getCell(0).getStringCellValue());
            assertEquals("公办", workbook.getSheetAt(0).getRow(2).getCell(2).getStringCellValue());
        }
    }
}
