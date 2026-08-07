package com.redculture.platform.service.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.entity.CatalogImportBatch;
import com.redculture.platform.entity.CatalogImportRow;
import com.redculture.platform.enums.EntityType;
import com.redculture.platform.mapper.CatalogImportBatchMapper;
import com.redculture.platform.mapper.CatalogImportRowMapper;
import com.redculture.platform.vo.admin.CatalogEntityRequest;
import com.redculture.platform.vo.admin.CatalogMediaRequest;
import com.redculture.platform.vo.admin.CatalogSourceRequest;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@Service
public class CatalogImportService {
    private static final Map<String, EntityType> SHEETS = Map.of("资源", EntityType.RESOURCE, "遗址", EntityType.SITE, "纪念馆", EntityType.MEMORIAL, "人物", EntityType.HERO, "事件", EntityType.EVENT, "故事", EntityType.STORY);
    private final CatalogImportBatchMapper batchMapper;
    private final CatalogImportRowMapper rowMapper;
    private final CatalogAdminService catalogService;
    private final ObjectMapper objectMapper;

    public CatalogImportService(CatalogImportBatchMapper batchMapper, CatalogImportRowMapper rowMapper, CatalogAdminService catalogService, ObjectMapper objectMapper) { this.batchMapper=batchMapper;this.rowMapper=rowMapper;this.catalogService=catalogService;this.objectMapper=objectMapper; }

    @Transactional
    public CatalogImportBatch preview(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getOriginalFilename() == null || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx")) throw new IllegalArgumentException("仅支持 .xlsx 文件");
        CatalogImportBatch batch=new CatalogImportBatch(); batch.setFileName(file.getOriginalFilename()); batch.setStatus("PREVIEWED"); batch.setTotalRows(0);batch.setValidRows(0);batch.setInvalidRows(0);batch.setDuplicateRows(0);batchMapper.insert(batch);
        Set<String> seen=new HashSet<>(); List<CatalogImportRow> rows=new ArrayList<>(); DataFormatter formatter=new DataFormatter();
        try(XSSFWorkbook workbook=new XSSFWorkbook(file.getInputStream())) {
            for(int sheetIndex=0;sheetIndex<workbook.getNumberOfSheets();sheetIndex++) { var sheet=workbook.getSheetAt(sheetIndex); String sheetName=sheet.getSheetName(); if("关系".equals(sheetName)){ readRelations(batch.getBatchId(),sheet,formatter,rows); continue; } EntityType type=SHEETS.get(sheetName); if(type==null) continue;
                for(int rowIndex=1;rowIndex<=sheet.getLastRowNum();rowIndex++){var source=sheet.getRow(rowIndex); if(source==null||blank(source,formatter))continue; CatalogImportRow row=new CatalogImportRow();row.setBatchId(batch.getBatchId());row.setSheetName(sheetName);row.setRowNumber(rowIndex+1);row.setEntityType(type.getValue()); String code=cell(source,0,formatter);String name=cell(source,1,formatter); if(code.isBlank()||name.isBlank()){row.setValidationStatus("INVALID");row.setValidationMessage("编码和名称不能为空");}else if(!seen.add(type.getValue()+":"+code.trim().toLowerCase(Locale.ROOT))){row.setValidationStatus("DUPLICATE");row.setValidationMessage("工作簿内存在重复编码");}else{row.setValidationStatus("VALID");row.setValidationMessage("校验通过");} row.setPayloadJson(payload(type,source,formatter));rows.add(row); }
            }
        } catch(IOException exception){throw new IllegalArgumentException("无法读取 Excel 文件",exception);}
        for(CatalogImportRow row:rows){rowMapper.insert(row); batch.setTotalRows(batch.getTotalRows()+1); if("VALID".equals(row.getValidationStatus()))batch.setValidRows(batch.getValidRows()+1);else if("DUPLICATE".equals(row.getValidationStatus()))batch.setDuplicateRows(batch.getDuplicateRows()+1);else batch.setInvalidRows(batch.getInvalidRows()+1);} batchMapper.updateById(batch);return batch;
    }

    @Transactional
    public CatalogImportBatch confirm(Long batchId) {
        CatalogImportBatch batch=requireBatch(batchId); if(!"PREVIEWED".equals(batch.getStatus()))throw new IllegalArgumentException("该批次不能重复确认"); List<CatalogImportRow> rows=rowMapper.selectList(new LambdaQueryWrapper<CatalogImportRow>().eq(CatalogImportRow::getBatchId,batchId).orderByAsc(CatalogImportRow::getRowId));
        for(CatalogImportRow row:rows){ if(!"VALID".equals(row.getValidationStatus())||"relation".equals(row.getEntityType()))continue; try{CatalogEntityRequest request=objectMapper.readValue(row.getPayloadJson(),CatalogEntityRequest.class); var entity=catalogService.create(request); catalogService.submitForReview(request.getEntityType(),entity.getEntityId());row.setImportedEntityId(entity.getEntityId());row.setValidationStatus("IMPORTED");row.setValidationMessage("已导入，等待审核");}catch(Exception exception){row.setValidationStatus("FAILED");row.setValidationMessage(message(exception));}rowMapper.updateById(row); }
        batch.setStatus("CONFIRMED");batchMapper.updateById(batch);return batch;
    }

    public List<CatalogImportRow> rows(Long batchId){requireBatch(batchId);return rowMapper.selectList(new LambdaQueryWrapper<CatalogImportRow>().eq(CatalogImportRow::getBatchId,batchId).orderByAsc(CatalogImportRow::getRowId));}
    public String report(Long batchId){StringBuilder out=new StringBuilder("工作表,行号,状态,说明\n");for(CatalogImportRow row:rows(batchId))out.append(csv(row.getSheetName())).append(',').append(row.getRowNumber()).append(',').append(csv(row.getValidationStatus())).append(',').append(csv(row.getValidationMessage())).append('\n');return out.toString();}
    private CatalogImportBatch requireBatch(Long id){CatalogImportBatch batch=batchMapper.selectById(id);if(batch==null)throw new IllegalArgumentException("导入批次不存在");return batch;}
    private void readRelations(Long batchId,org.apache.poi.ss.usermodel.Sheet sheet,DataFormatter formatter,List<CatalogImportRow> rows){for(int index=1;index<=sheet.getLastRowNum();index++){var source=sheet.getRow(index);if(source==null||blank(source,formatter))continue;CatalogImportRow row=new CatalogImportRow();row.setBatchId(batchId);row.setSheetName("关系");row.setRowNumber(index+1);row.setEntityType("relation");String a=cell(source,0,formatter),b=cell(source,1,formatter),c=cell(source,2,formatter),d=cell(source,3,formatter),e=cell(source,4,formatter);row.setPayloadJson(json(Map.of("sourceType",a,"sourceCode",b,"relationType",c,"targetType",d,"targetCode",e,"remark",cell(source,5,formatter))));if(a.isBlank()||b.isBlank()||c.isBlank()||d.isBlank()||e.isBlank()){row.setValidationStatus("INVALID");row.setValidationMessage("关系字段不能为空");}else{row.setValidationStatus("VALID");row.setValidationMessage("关系将在实体审核发布后维护");}rows.add(row);}}
    private String payload(EntityType type,org.apache.poi.ss.usermodel.Row row,DataFormatter formatter){CatalogEntityRequest request=new CatalogEntityRequest();request.setEntityType(type);request.setCode(cell(row,0,formatter));request.setName(cell(row,1,formatter));request.setAlias(cell(row,2,formatter));request.setRegionId(longValue(cell(row,3,formatter)));request.setAddress(cell(row,4,formatter));request.setLongitude(decimal(cell(row,5,formatter)));request.setLatitude(decimal(cell(row,6,formatter)));request.setSummary(cell(row,7,formatter));request.setDetail(cell(row,8,formatter));String image=cell(row,9,formatter);if(!image.isBlank()){CatalogMediaRequest media=new CatalogMediaRequest();media.setMediaUrl(image);media.setMediaType("image");media.setPrimary(true);request.setMedia(List.of(media));}String url=cell(row,10,formatter);if(!url.isBlank()){CatalogSourceRequest source=new CatalogSourceRequest();source.setSourceUrl(url);source.setCredibilityScore(intValue(cell(row,11,formatter)));request.setSources(List.of(source));}return json(request);}
    private String cell(org.apache.poi.ss.usermodel.Row row,int index,DataFormatter formatter){var cell=row.getCell(index);return cell==null?"":formatter.formatCellValue(cell).trim();}
    private boolean blank(org.apache.poi.ss.usermodel.Row row,DataFormatter formatter){for(int i=0;i<row.getLastCellNum();i++)if(!cell(row,i,formatter).isBlank())return false;return true;}
    private Long longValue(String value){try{return value.isBlank()?null:Long.parseLong(value);}catch(NumberFormatException e){return null;}}
    private java.math.BigDecimal decimal(String value){try{return value.isBlank()?null:new java.math.BigDecimal(value);}catch(NumberFormatException e){return null;}}
    private Integer intValue(String value){try{return value.isBlank()?null:Integer.parseInt(value);}catch(NumberFormatException e){return null;}}
    private String json(Object value){try{return objectMapper.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private String message(Exception exception){String value=exception.getMessage();return value==null?"导入失败":value.substring(0,Math.min(value.length(),300));}
    private String csv(String value){return '"'+String.valueOf(value==null?"":value).replace("\"","\"\"")+'"';}
}
