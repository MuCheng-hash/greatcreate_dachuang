package com.redculture.platform.service.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.entity.AdministrativeRegion;
import com.redculture.platform.entity.CatalogImportBatch;
import com.redculture.platform.entity.CatalogImportRow;
import com.redculture.platform.enums.EntityType;
import com.redculture.platform.enums.RegionLevel;
import com.redculture.platform.enums.ResourceCategory;
import com.redculture.platform.mapper.AdministrativeRegionMapper;
import com.redculture.platform.mapper.CatalogImportBatchMapper;
import com.redculture.platform.mapper.CatalogImportRowMapper;
import com.redculture.platform.vo.admin.CatalogEntityRequest;
import com.redculture.platform.vo.admin.CatalogEntityVO;
import com.redculture.platform.vo.admin.CatalogMediaRequest;
import com.redculture.platform.vo.admin.CatalogRelationRequest;
import com.redculture.platform.vo.admin.CatalogRelationVO;
import com.redculture.platform.vo.admin.CatalogSourceRequest;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CatalogImportService {
    private static final Map<String, EntityType> SHEETS = sheetTypes();
    private static final Map<String, List<String>> ENTITY_HEADER_ALIASES = entityHeaderAliases();
    private static final Map<String, List<String>> RELATION_HEADER_ALIASES = relationHeaderAliases();
    private static final Map<String, String> CATEGORY_LABELS = Map.ofEntries(
            Map.entry("红色文化", "red_culture"),
            Map.entry("革命遗址", "red_culture"),
            Map.entry("革命英雄", "red_culture"),
            Map.entry("革命事件", "red_culture"),
            Map.entry("纪念馆", "patriotism_base"),
            Map.entry("非遗文化", "intangible_culture"),
            Map.entry("非物质文化遗产", "intangible_culture"),
            Map.entry("传统文化", "traditional_culture"),
            Map.entry("地方历史", "local_history"),
            Map.entry("公共文化", "public_culture"),
            Map.entry("劳动教育", "labor_education"),
            Map.entry("公益实践", "public_welfare"),
            Map.entry("公益教育", "public_welfare"),
            Map.entry("生态文明", "ecological_civilization"),
            Map.entry("爱国主义基地", "patriotism_base"),
            Map.entry("社会实践", "social_practice"),
            Map.entry("其他", "other")
    );

    private final CatalogImportBatchMapper batchMapper;
    private final CatalogImportRowMapper rowMapper;
    private final CatalogAdminService catalogService;
    private final CatalogProjectionService projectionService;
    private final AdministrativeRegionMapper regionMapper;
    private final ObjectMapper objectMapper;

    public CatalogImportService(CatalogImportBatchMapper batchMapper, CatalogImportRowMapper rowMapper,
                                CatalogAdminService catalogService, CatalogProjectionService projectionService, AdministrativeRegionMapper regionMapper,
                                ObjectMapper objectMapper) {
        this.batchMapper = batchMapper;
        this.rowMapper = rowMapper;
        this.catalogService = catalogService;
        this.projectionService = projectionService;
        this.regionMapper = regionMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CatalogImportBatch preview(MultipartFile file) {
        validateFile(file);
        CatalogImportBatch batch = new CatalogImportBatch();
        batch.setFileName(file.getOriginalFilename());
        batch.setStatus("PREVIEWED");
        batch.setTotalRows(0);
        batch.setValidRows(0);
        batch.setInvalidRows(0);
        batch.setDuplicateRows(0);
        batchMapper.insert(batch);

        List<CatalogImportRow> rows = new ArrayList<>();
        Map<String, EntityRef> workbookEntities = new HashMap<>();
        Set<String> seenEntityCodes = new HashSet<>();
        Set<String> seenRelations = new HashSet<>();
        DataFormatter formatter = new DataFormatter();
        try (XSSFWorkbook workbook = new XSSFWorkbook(file.getInputStream())) {
            // 先读实体，再读关系，保证关系工作表在任意位置都能引用本批次新实体。
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                Sheet sheet = workbook.getSheetAt(index);
                EntityType type = SHEETS.get(sheet.getSheetName());
                if (type != null) {
                    readEntitySheet(batch.getBatchId(), sheet, type, formatter, rows, workbookEntities, seenEntityCodes);
                }
            }
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                Sheet sheet = workbook.getSheetAt(index);
                if ("关系".equals(sheet.getSheetName())) {
                    readRelationSheet(batch.getBatchId(), sheet, formatter, rows, workbookEntities, seenRelations);
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取 Excel 文件", exception);
        }

        for (CatalogImportRow row : rows) {
            rowMapper.insert(row);
            batch.setTotalRows(number(batch.getTotalRows()) + 1);
            if ("VALID".equals(row.getValidationStatus())) {
                batch.setValidRows(number(batch.getValidRows()) + 1);
            } else if ("DUPLICATE".equals(row.getValidationStatus())) {
                batch.setDuplicateRows(number(batch.getDuplicateRows()) + 1);
            } else {
                batch.setInvalidRows(number(batch.getInvalidRows()) + 1);
            }
        }
        batchMapper.updateById(batch);
        return batch;
    }

    @Transactional
    public CatalogImportBatch confirm(Long batchId) {
        CatalogImportBatch batch = requireBatch(batchId);
        if (!"PREVIEWED".equals(batch.getStatus())) {
            throw new IllegalArgumentException("该批次不能重复确认");
        }
        List<CatalogImportRow> rows = rowMapper.selectList(new LambdaQueryWrapper<CatalogImportRow>()
                .eq(CatalogImportRow::getBatchId, batchId).orderByAsc(CatalogImportRow::getRowId));
        Map<String, Long> importedEntityIds = new HashMap<>();

        for (CatalogImportRow row : rows) {
            if (!"VALID".equals(row.getValidationStatus()) || isRelation(row)) continue;
            try {
                CatalogEntityRequest request = objectMapper.readValue(row.getPayloadJson(), CatalogEntityRequest.class);
                CatalogEntityVO existing = catalogService.findByCode(request.getEntityType(), request.getCode());
                if (existing != null) {
                    markDuplicate(row, "确认导入时发现数据库已有相同编码");
                    batch.setValidRows(Math.max(0, number(batch.getValidRows()) - 1));
                    batch.setDuplicateRows(number(batch.getDuplicateRows()) + 1);
                    rowMapper.updateById(row);
                    continue;
                }
                CatalogEntityVO entity = catalogService.create(request);
                if (entity == null || entity.getEntityId() == null) {
                    throw new IllegalArgumentException("实体创建未返回实体 ID");
                }
                projectionService.projectEntity(entity);
                row.setImportedEntityId(entity.getEntityId());
                row.setValidationStatus("IMPORTED");
                row.setValidationMessage("已导入并发布，图谱与检索投影已排队");
                importedEntityIds.put(entityKey(request.getEntityType(), request.getCode()), entity.getEntityId());
            } catch (Exception exception) {
                row.setValidationStatus("FAILED");
                row.setValidationMessage(message(exception));
            }
            rowMapper.updateById(row);
        }

        for (CatalogImportRow row : rows) {
            if (!"VALID".equals(row.getValidationStatus()) || !isRelation(row)) continue;
            try {
                JsonNode payload = objectMapper.readTree(row.getPayloadJson());
                EntityType sourceType = parseEntityType(payload.path("sourceType").asText());
                EntityType targetType = parseEntityType(payload.path("targetType").asText());
                String sourceCode = payload.path("sourceCode").asText("").trim();
                String targetCode = payload.path("targetCode").asText("").trim();
                CatalogRelationRequest request = new CatalogRelationRequest();
                request.setSourceType(sourceType);
                request.setSourceId(resolveEntityId(sourceType, sourceCode, importedEntityIds));
                request.setTargetType(targetType);
                request.setTargetId(resolveEntityId(targetType, targetCode, importedEntityIds));
                request.setRelationType(payload.path("relationType").asText());
                request.setRemark(payload.path("remark").asText(null));
                if (catalogService.relationExists(request)) {
                    markDuplicate(row, "确认导入时发现数据库已有相同关系");
                } else {
                    CatalogRelationVO relation = catalogService.createImportedRelation(request);
                    if (relation != null) projectionService.projectRelation(relation);
                    row.setImportedEntityId(relation == null ? null : relation.getRelationId());
                    row.setValidationStatus("IMPORTED");
                    row.setValidationMessage("关系已写入，图谱投影已排队");
                }
            } catch (Exception exception) {
                row.setValidationStatus("FAILED");
                row.setValidationMessage(message(exception));
            }
            rowMapper.updateById(row);
        }

        batch.setStatus("CONFIRMED");
        batchMapper.updateById(batch);
        return batch;
    }

    public List<CatalogImportRow> rows(Long batchId) {
        requireBatch(batchId);
        return rowMapper.selectList(new LambdaQueryWrapper<CatalogImportRow>()
                .eq(CatalogImportRow::getBatchId, batchId).orderByAsc(CatalogImportRow::getRowId));
    }

    public String report(Long batchId) {
        StringBuilder out = new StringBuilder("工作表,行号,状态,说明\n");
        for (CatalogImportRow row : rows(batchId)) {
            out.append(csv(row.getSheetName())).append(',').append(row.getRowNumber()).append(',')
                    .append(csv(row.getValidationStatus())).append(',').append(csv(row.getValidationMessage())).append('\n');
        }
        return out.toString();
    }

    private void readEntitySheet(Long batchId, Sheet sheet, EntityType type, DataFormatter formatter,
                                  List<CatalogImportRow> rows, Map<String, EntityRef> workbookEntities,
                                  Set<String> seenEntityCodes) {
        HeaderMap headers = readHeaders(sheet, type, formatter, ENTITY_HEADER_ALIASES);
        for (int index = 1; index <= sheet.getLastRowNum(); index++) {
            Row source = sheet.getRow(index);
            if (source == null || blank(source, formatter)) continue;
            CatalogImportRow row = baseRow(batchId, sheet.getSheetName(), index + 1, type.getValue());
            List<String> errors = new ArrayList<>();
            CatalogEntityRequest request = buildEntityRequest(type, source, headers, formatter, errors);
            String code = value(source, headers, "code", formatter);
            String key = entityKey(type, code);
            boolean duplicate = false;
            if (StringUtils.hasText(code)) {
                duplicate = !seenEntityCodes.add(key);
                if (duplicate) errors.add("工作簿内存在重复编码");
                CatalogEntityVO existing = catalogService.findByCode(type, code);
                if (existing != null) {
                    duplicate = true;
                    errors.add("数据库已有相同编码");
                }
            }
            row.setPayloadJson(json(request));
            row.setValidationStatus(duplicate ? "DUPLICATE" : errors.isEmpty() ? "VALID" : "INVALID");
            row.setValidationMessage(errors.isEmpty() ? "校验通过" : String.join("；", errors));
            rows.add(row);
            if ("VALID".equals(row.getValidationStatus())) {
                workbookEntities.put(key, new EntityRef(type, code.trim(), row, request));
            }
        }
    }

    private void readRelationSheet(Long batchId, Sheet sheet, DataFormatter formatter,
                                   List<CatalogImportRow> rows, Map<String, EntityRef> workbookEntities,
                                   Set<String> seenRelations) {
        HeaderMap headers = readHeaders(sheet, null, formatter, RELATION_HEADER_ALIASES);
        for (int index = 1; index <= sheet.getLastRowNum(); index++) {
            Row source = sheet.getRow(index);
            if (source == null || blank(source, formatter)) continue;
            CatalogImportRow row = baseRow(batchId, sheet.getSheetName(), index + 1, "relation");
            List<String> errors = new ArrayList<>();
            String sourceTypeText = value(source, headers, "sourceType", formatter);
            String sourceCode = value(source, headers, "sourceCode", formatter);
            String relationType = value(source, headers, "relationType", formatter);
            String targetTypeText = value(source, headers, "targetType", formatter);
            String targetCode = value(source, headers, "targetCode", formatter);
            String remark = value(source, headers, "remark", formatter);
            EntityType sourceType = parseEntityType(sourceTypeText, errors, "源实体类型");
            EntityType targetType = parseEntityType(targetTypeText, errors, "目标实体类型");
            if (!StringUtils.hasText(sourceCode) || !StringUtils.hasText(targetCode) || !StringUtils.hasText(relationType)) {
                errors.add("关系两端编码和关系类型不能为空");
            }
            if (sourceType != null && targetType != null && StringUtils.hasText(relationType)) {
                try {
                    catalogService.validateRelationType(sourceType, targetType, relationType);
                } catch (IllegalArgumentException exception) {
                    errors.add(exception.getMessage());
                }
                if (errors.isEmpty()) {
                    EntityRef sourceRef = workbookEntities.get(entityKey(sourceType, sourceCode));
                    EntityRef targetRef = workbookEntities.get(entityKey(targetType, targetCode));
                    CatalogEntityVO sourceExisting = sourceRef == null ? catalogService.findByCode(sourceType, sourceCode) : null;
                    CatalogEntityVO targetExisting = targetRef == null ? catalogService.findByCode(targetType, targetCode) : null;
                    if (sourceRef == null && sourceExisting == null) errors.add("源实体编码不存在或未通过预检");
                    if (targetRef == null && targetExisting == null) errors.add("目标实体编码不存在或未通过预检");
                    if (errors.isEmpty()) {
                        String normalizedRelation = normalizeRelation(relationType);
                        String relationKey = entityKey(sourceType, sourceCode) + "->" + entityKey(targetType, targetCode) + ":" + normalizedRelation;
                        if (!seenRelations.add(relationKey)) {
                            errors.add("工作簿内存在重复关系");
                        } else if (sourceExisting != null && targetExisting != null) {
                            CatalogRelationRequest request = relationRequest(sourceType, sourceExisting.getEntityId(), targetType, targetExisting.getEntityId(), normalizedRelation, remark);
                            if (catalogService.relationExists(request)) errors.add("数据库已有相同关系");
                        }
                    }
                }
            }
            row.setPayloadJson(relationJson(sourceType, sourceCode, normalizeRelation(relationType), targetType, targetCode, remark));
            row.setValidationStatus(errors.isEmpty() ? "VALID" : "INVALID");
            row.setValidationMessage(errors.isEmpty() ? "关系校验通过，确认后写入并投影" : String.join("；", errors));
            rows.add(row);
        }
    }

    private CatalogEntityRequest buildEntityRequest(EntityType type, Row row, HeaderMap headers,
                                                    DataFormatter formatter, List<String> errors) {
        CatalogEntityRequest request = new CatalogEntityRequest();
        request.setEntityType(type);
        request.setCode(value(row, headers, "code", formatter));
        request.setName(value(row, headers, "name", formatter));
        request.setAddress(value(row, headers, "address", formatter));
        request.setSummary(value(row, headers, "summary", formatter));
        request.setDetail(value(row, headers, "detail", formatter));
        request.setTargetGrade(value(row, headers, "targetGrade", formatter));
        request.setResourceSubcategory(value(row, headers, "resourceSubcategory", formatter));
        request.setOrganizationName(value(row, headers, "organizationName", formatter));
        request.setContactPhone(value(row, headers, "contactPhone", formatter));
        request.setOpeningTimeDesc(value(row, headers, "openingTimeDesc", formatter));
        request.setActivitySuggestion(value(row, headers, "activitySuggestion", formatter));
        request.setSafetyNote(value(row, headers, "safetyNote", formatter));

        if (!StringUtils.hasText(request.getCode())) errors.add("编码不能为空");
        if (!StringUtils.hasText(request.getName())) errors.add("名称不能为空");

        String provinceName = value(row, headers, "provinceName", formatter);
        String cityName = value(row, headers, "cityName", formatter);
        String countyName = value(row, headers, "countyName", formatter);
        String townshipName = value(row, headers, "townshipName", formatter);
        boolean hasRegionPath = StringUtils.hasText(provinceName) || StringUtils.hasText(cityName)
                || StringUtils.hasText(countyName) || StringUtils.hasText(townshipName);
        String regionText = value(row, headers, "region", formatter);
        if (hasRegionPath) {
            if (type == EntityType.RESOURCE && !StringUtils.hasText(countyName)) {
                errors.add("资源导入至少需要填写区县名称");
            }
            try {
                request.setRegionId(resolveRegionPath(provinceName, cityName, countyName, townshipName));
            } catch (IllegalArgumentException exception) {
                errors.add(exception.getMessage());
            }
        } else if (!StringUtils.hasText(regionText)) {
            if (type == EntityType.RESOURCE) errors.add("请填写区县名称（乡镇名称可选）");
        } else {
            try {
                request.setRegionId(resolveRegion(regionText));
            } catch (IllegalArgumentException exception) {
                errors.add(exception.getMessage());
            }
        }

        String longitudeText = value(row, headers, "longitude", formatter);
        String latitudeText = value(row, headers, "latitude", formatter);
        request.setLongitude(decimal(longitudeText, "经度", errors));
        request.setLatitude(decimal(latitudeText, "纬度", errors));
        if (type == EntityType.RESOURCE) {
            if (!StringUtils.hasText(request.getAddress())) errors.add("地址不能为空");
            if (!StringUtils.hasText(longitudeText)) errors.add("经度不能为空");
            if (!StringUtils.hasText(latitudeText)) errors.add("纬度不能为空");
            if (!StringUtils.hasText(request.getSummary())) errors.add("简介不能为空");
            if (!StringUtils.hasText(request.getDetail())) errors.add("教育价值不能为空");
            if (!StringUtils.hasText(request.getTargetGrade())) errors.add("适合学段不能为空");
        }
        if (request.getLongitude() != null && (request.getLongitude().compareTo(BigDecimal.valueOf(-180)) < 0 || request.getLongitude().compareTo(BigDecimal.valueOf(180)) > 0)) {
            errors.add("经度必须在 -180 到 180 之间");
        }
        if (request.getLatitude() != null && (request.getLatitude().compareTo(BigDecimal.valueOf(-90)) < 0 || request.getLatitude().compareTo(BigDecimal.valueOf(90)) > 0)) {
            errors.add("纬度必须在 -90 到 90 之间");
        }

        String categoryText = value(row, headers, "resourceCategory", formatter);
        if (type == EntityType.RESOURCE) {
            if (!StringUtils.hasText(categoryText)) {
                errors.add("资源类型不能为空");
            } else {
                try {
                    request.setResourceCategory(resourceCategory(categoryText));
                } catch (IllegalArgumentException exception) {
                    errors.add(exception.getMessage());
                }
            }
        }

        String image = value(row, headers, "imageUrl", formatter);
        if (StringUtils.hasText(image)) {
            CatalogMediaRequest media = new CatalogMediaRequest();
            media.setMediaUrl(image);
            media.setMediaType("image");
            media.setPrimary(true);
            request.setMedia(List.of(media));
        } else {
            request.setMedia(Collections.emptyList());
        }
        String dataSource = value(row, headers, "dataSource", formatter);
        if (type == EntityType.RESOURCE && !StringUtils.hasText(dataSource)) errors.add("数据来源不能为空");
        if (StringUtils.hasText(dataSource)) {
            CatalogSourceRequest source = new CatalogSourceRequest();
            source.setSourceUrl(dataSource);
            request.setSources(List.of(source));
        } else {
            request.setSources(Collections.emptyList());
        }
        request.setReservationRequired(booleanValue(value(row, headers, "reservationRequired", formatter), errors));
        request.setRecommendedVisitMinutes(integer(value(row, headers, "recommendedVisitMinutes", formatter), "建议时长", errors));
        return request;
    }

    private Long resolveRegion(String value) {
        String normalized = value.trim();
        LambdaQueryWrapper<AdministrativeRegion> query = new LambdaQueryWrapper<>();
        if (normalized.matches("\\d+")) {
            try {
                query.eq(AdministrativeRegion::getRegionId, Long.parseLong(normalized)).or().eq(AdministrativeRegion::getAdcode, normalized);
            } catch (NumberFormatException exception) {
                query.eq(AdministrativeRegion::getAdcode, normalized);
            }
        } else {
            query.eq(AdministrativeRegion::getRegionName, normalized).or().eq(AdministrativeRegion::getAdcode, normalized);
        }
        List<AdministrativeRegion> matches = regionMapper.selectList(query);
        if (matches == null) matches = List.of();
        Map<Long, AdministrativeRegion> unique = matches.stream().filter(item -> item.getRegionId() != null)
                .collect(Collectors.toMap(AdministrativeRegion::getRegionId, item -> item, (left, right) -> left, LinkedHashMap::new));
        if (unique.isEmpty()) throw new IllegalArgumentException("行政区域不存在: " + value);
        if (unique.size() > 1) throw new IllegalArgumentException("行政区域名称或 adcode 匹配到多个区域: " + value);
        return unique.values().iterator().next().getRegionId();
    }

    /**
     * 解析输入的最细行政区划，并校验每一条输入的父子级联关系。
     * 对旧版工作簿仍通过 {@link #resolveRegion(String)} 支持原有的单行政区划列。
     */
    private Long resolveRegionPath(String provinceName, String cityName, String countyName, String townshipName) {
        AdministrativeRegion province = resolveRegionByName(RegionLevel.PROVINCE, provinceName, null, "省份");
        AdministrativeRegion city = resolveRegionByName(RegionLevel.CITY, cityName,
                province == null ? null : province.getRegionId(), "城市");
        if (city != null && province == null) {
            province = parentOf(city, RegionLevel.PROVINCE, "城市未关联有效省份");
        }

        AdministrativeRegion county = resolveRegionByName(RegionLevel.COUNTY, countyName,
                city == null ? null : city.getRegionId(), "区县");
        if (county != null && city == null) {
            city = parentOf(county, RegionLevel.CITY, "区县未关联有效城市");
        }
        if (city != null && province == null) {
            province = parentOf(city, RegionLevel.PROVINCE, "城市未关联有效省份");
        }

        AdministrativeRegion township = resolveRegionByName(RegionLevel.TOWNSHIP, townshipName,
                county == null ? null : county.getRegionId(), "乡镇");
        if (township != null && county == null) {
            county = parentOf(township, RegionLevel.COUNTY, "乡镇未关联有效区县");
        }
        if (county != null && city == null) {
            city = parentOf(county, RegionLevel.CITY, "区县未关联有效城市");
        }
        if (city != null && province == null) {
            province = parentOf(city, RegionLevel.PROVINCE, "城市未关联有效省份");
        }

        if (township != null) return township.getRegionId();
        if (county != null) return county.getRegionId();
        if (city != null) return city.getRegionId();
        if (province != null) return province.getRegionId();
        throw new IllegalArgumentException("请至少填写一个行政区划名称");
    }

    private AdministrativeRegion resolveRegionByName(RegionLevel level, String name, Long parentId, String label) {
        if (!StringUtils.hasText(name)) return null;
        List<AdministrativeRegion> candidates = regionMapper.selectList(new LambdaQueryWrapper<AdministrativeRegion>()
                .eq(AdministrativeRegion::getRegionLevel, level)
                .eq(parentId != null, AdministrativeRegion::getParentRegionId, parentId));
        String normalized = normalizeRegionName(name);
        List<AdministrativeRegion> matches = candidates.stream()
                .filter(item -> normalizeRegionName(item.getRegionName()).equals(normalized))
                .toList();
        if (matches.isEmpty()) throw new IllegalArgumentException("无法找到" + label + "“" + name.trim() + "”");
        if (matches.size() > 1) throw new IllegalArgumentException(label + "“" + name.trim() + "”存在歧义，请补充上级行政区");
        return matches.getFirst();
    }

    private AdministrativeRegion parentOf(AdministrativeRegion child, RegionLevel expectedLevel, String message) {
        if (child == null || child.getParentRegionId() == null) throw new IllegalArgumentException(message);
        AdministrativeRegion parent = regionMapper.selectById(child.getParentRegionId());
        if (parent == null || parent.getRegionLevel() != expectedLevel) throw new IllegalArgumentException(message);
        return parent;
    }

    private String normalizeRegionName(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("[\\s　]+", "")
                .replaceFirst("(特别行政区|自治区|省|市|区|县|镇|乡|街道)$", "");
    }

    private ResourceCategory resourceCategory(String value) {
        String normalized = value.trim();
        String mapped = CATEGORY_LABELS.get(normalized);
        return ResourceCategory.fromValue(mapped == null ? normalized : mapped);
    }

    private BigDecimal decimal(String value, String label, List<String> errors) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException exception) {
            errors.add(label + "必须是数字");
            return null;
        }
    }

    private Integer integer(String value, String label, List<String> errors) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            errors.add(label + "必须是整数");
            return null;
        }
    }

    private Boolean booleanValue(String value, List<String> errors) {
        if (!StringUtils.hasText(value)) return null;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "是", "需要", "yes" -> true;
            case "false", "0", "否", "不需要", "no" -> false;
            default -> {
                errors.add("需要预约必须是 是/否 或 true/false");
                yield null;
            }
        };
    }

    private EntityType parseEntityType(String value, List<String> errors, String label) {
        if (!StringUtils.hasText(value)) {
            errors.add(label + "不能为空");
            return null;
        }
        try {
            return parseEntityType(value);
        } catch (IllegalArgumentException exception) {
            errors.add(label + "不支持: " + value);
            return null;
        }
    }

    private EntityType parseEntityType(String value) {
        String normalized = value.trim();
        for (Map.Entry<String, EntityType> entry : SHEETS.entrySet()) {
            if (entry.getKey().equals(normalized)) return entry.getValue();
        }
        for (EntityType type : EntityType.values()) {
            if (type.getValue().equalsIgnoreCase(normalized) || type.name().equalsIgnoreCase(normalized)) return type;
        }
        throw new IllegalArgumentException("unsupported entity type");
    }

    private Long resolveEntityId(EntityType type, String code, Map<String, Long> importedEntityIds) {
        Long imported = importedEntityIds.get(entityKey(type, code));
        if (imported != null) return imported;
        CatalogEntityVO existing = catalogService.findByCode(type, code);
        if (existing == null || existing.getEntityId() == null) throw new IllegalArgumentException("关系实体不存在: " + code);
        return existing.getEntityId();
    }

    private CatalogRelationRequest relationRequest(EntityType sourceType, Long sourceId, EntityType targetType,
                                                   Long targetId, String relationType, String remark) {
        CatalogRelationRequest request = new CatalogRelationRequest();
        request.setSourceType(sourceType);
        request.setSourceId(sourceId);
        request.setTargetType(targetType);
        request.setTargetId(targetId);
        request.setRelationType(relationType);
        request.setRemark(remark);
        return request;
    }

    private String relationJson(EntityType sourceType, String sourceCode, String relationType,
                                EntityType targetType, String targetCode, String remark) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("sourceType", sourceType == null ? null : sourceType.getValue());
        payload.put("sourceCode", sourceCode);
        payload.put("relationType", relationType);
        payload.put("targetType", targetType == null ? null : targetType.getValue());
        payload.put("targetCode", targetCode);
        payload.put("remark", remark);
        return json(payload);
    }

    private HeaderMap readHeaders(Sheet sheet, EntityType type, DataFormatter formatter,
                                  Map<String, List<String>> aliases) {
        Row header = sheet.getRow(0);
        if (header == null) throw new IllegalArgumentException("工作表[" + sheet.getSheetName() + "]缺少表头");
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int index = 0; index < header.getLastCellNum(); index++) {
            String title = cell(header, index, formatter);
            String field = fieldForHeader(title, aliases);
            if (field == null) continue;
            if (columns.putIfAbsent(field, index) != null) {
                throw new IllegalArgumentException("工作表[" + sheet.getSheetName() + "]存在重复字段: " + title);
            }
        }
        List<String> required = type == null
                ? List.of("sourceType", "sourceCode", "relationType", "targetType", "targetCode") : List.of("code", "name");
        List<String> missing = required.stream().filter(field -> !columns.containsKey(field)).map(this::fieldLabel).toList();
        if (!missing.isEmpty()) throw new IllegalArgumentException("工作表[" + sheet.getSheetName() + "]缺少列: " + String.join("、", missing));
        return new HeaderMap(columns);
    }

    private String fieldForHeader(String title, Map<String, List<String>> aliases) {
        String normalized = normalizeHeader(title);
        if (normalized.isBlank()) return null;
        return aliases.entrySet().stream().filter(entry -> entry.getValue().stream().map(this::normalizeHeader).anyMatch(normalized::equals))
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    private String normalizeHeader(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-（）()：:./]", "");
    }

    private String value(Row row, HeaderMap headers, String field, DataFormatter formatter) {
        Integer index = headers.columns().get(field);
        return index == null ? "" : cell(row, index, formatter);
    }

    private String cell(Row row, int index, DataFormatter formatter) {
        org.apache.poi.ss.usermodel.Cell cell = row.getCell(index);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private boolean blank(Row row, DataFormatter formatter) {
        for (int index = 0; index < row.getLastCellNum(); index++) if (!cell(row, index, formatter).isBlank()) return false;
        return true;
    }

    private CatalogImportRow baseRow(Long batchId, String sheetName, int rowNumber, String entityType) {
        CatalogImportRow row = new CatalogImportRow();
        row.setBatchId(batchId);
        row.setSheetName(sheetName);
        row.setRowNumber(rowNumber);
        row.setEntityType(entityType);
        return row;
    }

    private CatalogImportBatch requireBatch(Long id) {
        CatalogImportBatch batch = batchMapper.selectById(id);
        if (batch == null) throw new IllegalArgumentException("导入批次不存在");
        return batch;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getOriginalFilename() == null
                || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IllegalArgumentException("仅支持 .xlsx 文件");
        }
    }

    private void markDuplicate(CatalogImportRow row, String message) {
        row.setValidationStatus("DUPLICATE");
        row.setValidationMessage(message);
    }

    private boolean isRelation(CatalogImportRow row) {
        return "relation".equals(row.getEntityType());
    }

    private int number(Integer value) {
        return value == null ? 0 : value;
    }

    private String entityKey(EntityType type, String code) {
        return (type == null ? "" : type.getValue()) + ":" + (code == null ? "" : code.trim().toLowerCase(Locale.ROOT));
    }

    private String normalizeRelation(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private String fieldLabel(String field) {
        return switch (field) {
            case "code" -> "编码";
            case "name" -> "资源名称/名称";
            case "resourceCategory" -> "资源类型";
            case "region" -> "行政区域";
            case "provinceName" -> "省份名称";
            case "cityName" -> "城市名称";
            case "countyName" -> "区县名称";
            case "townshipName" -> "乡镇名称";
            case "address" -> "地址";
            case "longitude" -> "经度";
            case "latitude" -> "纬度";
            case "summary" -> "简介";
            case "detail" -> "教育价值/详情";
            case "dataSource" -> "数据来源/来源URL";
            case "targetGrade" -> "适合学段";
            case "sourceType" -> "源实体类型";
            case "sourceCode" -> "源实体编码";
            case "relationType" -> "关系类型";
            case "targetType" -> "目标实体类型";
            case "targetCode" -> "目标实体编码";
            default -> field;
        };
    }

    private String message(Exception exception) {
        String value = exception.getMessage();
        return value == null ? "导入失败" : value.substring(0, Math.min(value.length(), 300));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String csv(String value) {
        return '"' + String.valueOf(value == null ? "" : value).replace("\"", "\"\"") + '"';
    }

    private static Map<String, EntityType> sheetTypes() {
        Map<String, EntityType> result = new LinkedHashMap<>();
        result.put("资源", EntityType.RESOURCE);
        result.put("遗址", EntityType.SITE);
        result.put("纪念馆", EntityType.MEMORIAL);
        result.put("人物", EntityType.HERO);
        result.put("事件", EntityType.EVENT);
        result.put("故事", EntityType.STORY);
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, List<String>> entityHeaderAliases() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("code", List.of("编码", "资源编码", "实体编码", "代码", "code", "resourcecode"));
        result.put("name", List.of("名称", "资源名称", "实体名称", "name", "resourcename"));
        result.put("resourceCategory", List.of("资源类型", "资源类别", "资源分类", "类型", "分类", "resourcecategory", "category"));
        result.put("region", List.of("行政区域", "行政区域名称", "区域", "区域名称", "地区", "regionname", "adcode"));
        result.put("provinceName", List.of("省份名称", "省份", "province_name", "provincename"));
        result.put("cityName", List.of("城市名称", "城市", "city_name", "cityname"));
        result.put("countyName", List.of("区县名称", "区县", "县区名称", "county_name", "countyname"));
        result.put("townshipName", List.of("乡镇名称", "乡镇", "街道名称", "township_name", "townshipname"));
        result.put("address", List.of("地址", "资源地址", "address"));
        result.put("longitude", List.of("经度", "longitude", "lng"));
        result.put("latitude", List.of("纬度", "latitude", "lat"));
        result.put("summary", List.of("简介", "资源简介", "摘要", "intro", "summary"));
        result.put("detail", List.of("教育价值", "详情", "教育意义", "教育价值说明", "detail", "educationvalue"));
        result.put("dataSource", List.of("数据来源", "来源URL", "来源", "数据来源URL", "sourceurl", "source", "datasource"));
        result.put("targetGrade", List.of("适合学段", "适用学段", "目标年级", "年级", "targetgrade", "grade"));
        result.put("resourceSubcategory", List.of("资源子类", "资源子分类", "子分类", "resourcesubcategory"));
        result.put("organizationName", List.of("所属机构", "机构名称", "机构", "organization"));
        result.put("contactPhone", List.of("联系电话", "电话", "contactphone"));
        result.put("openingTimeDesc", List.of("开放时间", "开放时间说明", "openingtime"));
        result.put("reservationRequired", List.of("需要预约", "是否预约", "reservationrequired"));
        result.put("recommendedVisitMinutes", List.of("建议时长", "推荐参观时长", "recommendedvisitminutes"));
        result.put("activitySuggestion", List.of("活动建议", "activitysuggestion"));
        result.put("safetyNote", List.of("安全提示", "注意事项", "safetynote"));
        result.put("imageUrl", List.of("图片URL", "图片地址", "imageurl"));
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, List<String>> relationHeaderAliases() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("sourceType", List.of("源实体类型", "源类型", "sourceentitytype", "sourcetype"));
        result.put("sourceCode", List.of("源实体编码", "源编码", "sourceentitycode", "sourcecode"));
        result.put("relationType", List.of("关系类型", "关系", "relationtype"));
        result.put("targetType", List.of("目标实体类型", "目标类型", "targetentitytype", "targettype"));
        result.put("targetCode", List.of("目标实体编码", "目标编码", "targetentitycode", "targetcode"));
        result.put("remark", List.of("备注", "说明", "remark", "comment"));
        return Collections.unmodifiableMap(result);
    }

    private record HeaderMap(Map<String, Integer> columns) { }

    private record EntityRef(EntityType type, String code, CatalogImportRow row, CatalogEntityRequest request) { }
}
