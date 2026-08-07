package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.entity.CatalogImportBatch;
import com.redculture.platform.entity.CatalogImportRow;
import com.redculture.platform.service.admin.CatalogImportService;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/admin/catalog/import")
public class CatalogImportController {
    private static final List<String> SHEETS = List.of("资源", "遗址", "纪念馆", "人物", "事件", "故事", "关系");
    private final CatalogImportService importService;

    public CatalogImportController(CatalogImportService importService) { this.importService = importService; }

    @GetMapping("/template")
    public void template(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode("思政资源图谱导入模板.xlsx", StandardCharsets.UTF_8));
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            for (String name : SHEETS) {
                var sheet = workbook.createSheet(name);
                var header = sheet.createRow(0);
                String[] columns = "关系".equals(name)
                        ? new String[]{"源实体类型", "源实体编码", "关系类型", "目标实体类型", "目标实体编码", "备注"}
                        : new String[]{"编码", "名称", "别名", "区域ID", "地址", "经度", "纬度", "简介", "详情", "图片URL", "来源URL", "可信度"};
                for (int index = 0; index < columns.length; index++) { header.createCell(index).setCellValue(columns[index]); sheet.setColumnWidth(index, 18 * 256); }
                sheet.createFreezePane(0, 1);
            }
            workbook.write(response.getOutputStream());
        }
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<CatalogImportBatch> preview(@RequestParam("file") MultipartFile file) {
        try { return ApiResponse.success("导入预检完成", importService.preview(file)); }
        catch (IllegalArgumentException exception) { return ApiResponse.fail(exception.getMessage()); }
    }

    @GetMapping("/{batchId}/rows")
    public ApiResponse<List<CatalogImportRow>> rows(@PathVariable Long batchId) {
        try { return ApiResponse.success(importService.rows(batchId)); }
        catch (IllegalArgumentException exception) { return ApiResponse.fail(exception.getMessage()); }
    }

    @PostMapping("/{batchId}/confirm")
    public ApiResponse<CatalogImportBatch> confirm(@PathVariable Long batchId) {
        try { return ApiResponse.success("合法行已导入待审核", importService.confirm(batchId)); }
        catch (IllegalArgumentException exception) { return ApiResponse.fail(exception.getMessage()); }
    }

    @GetMapping(value = "/{batchId}/report", produces = "text/csv;charset=UTF-8")
    public String report(@PathVariable Long batchId, HttpServletResponse response) {
        response.setHeader("Content-Disposition", "attachment; filename=import-report-" + batchId + ".csv");
        return importService.report(batchId);
    }
}
