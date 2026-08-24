package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.entity.School;
import com.redculture.platform.entity.AdministrativeRegion;
import com.redculture.platform.enums.RegionLevel;
import com.redculture.platform.enums.SchoolLevel;
import com.redculture.platform.enums.SchoolNature;
import com.redculture.platform.mapper.AdministrativeRegionMapper;
import com.redculture.platform.mapper.SchoolMapper;
import com.redculture.platform.service.SchoolService;
import com.redculture.platform.vo.SchoolAdminVO;
import com.redculture.platform.vo.SchoolImportErrorVO;
import com.redculture.platform.vo.request.SchoolCreateRequest;
import com.redculture.platform.vo.request.SchoolUpdateRequest;
import com.redculture.platform.vo.request.SchoolCsvImportRequest;
import com.redculture.platform.vo.SchoolImportResultVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class SchoolServiceImpl extends ServiceImpl<SchoolMapper, School> implements SchoolService {

    private static final long DEFAULT_PAGE_NUM = 1L;
    private static final long DEFAULT_PAGE_SIZE = 10L;
    private static final long MAX_PAGE_SIZE = 100L;
    private static final long MAX_IMPORT_BYTES = 10L * 1024 * 1024;
    private static final List<String> IMPORT_HEADERS = List.of(
            "school_code", "school_name", "school_type", "school_level", "school_nature", "address",
            "longitude", "latitude", "province_name", "city_name", "county_name", "township_name",
            "intro", "contact_phone", "principal_name"
    );

    private final AdministrativeRegionMapper administrativeRegionMapper;

    public SchoolServiceImpl(AdministrativeRegionMapper administrativeRegionMapper) {
        this.administrativeRegionMapper = administrativeRegionMapper;
    }

    @Override
    @Transactional
    public SchoolAdminVO createSchool(SchoolCreateRequest request) {
        validateCreateRequest(request);

        School school = new School();
        fillSchoolForCreate(school, request);
        school.setActive(true);
        school.setReviewStatus("approved");
        save(school);
        return toSchoolAdminVO(school);
    }

    @Override
    @Transactional
    public SchoolAdminVO updateSchool(Long schoolId, SchoolUpdateRequest request) {
        School school = requireSchool(schoolId);
        fillSchoolForUpdate(school, request);
        updateById(school);
        return toSchoolAdminVO(getById(schoolId));
    }

    @Override
    @Transactional
    public void deleteSchool(Long schoolId) {
        School school = requireSchool(schoolId);
        removeById(school.getSchoolId());
    }

    @Override
    public SchoolAdminVO getSchoolAdminDetail(Long schoolId) {
        School school = getById(schoolId);
        return school == null ? null : toSchoolAdminVO(school);
    }

    @Override
    public PageResult<SchoolAdminVO> pageSchools(String keyword,
                                                 Long provinceRegionId,
                                                 Long cityRegionId,
                                                 Long countyRegionId,
                                                 Long townshipRegionId,
                                                 Long pageNum,
                                                 Long pageSize) {
        long safePageNum = pageNum == null || pageNum <= 0 ? DEFAULT_PAGE_NUM : pageNum;
        long safePageSize = pageSize == null || pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

        LambdaQueryWrapper<School> wrapper = new LambdaQueryWrapper<School>()
                .eq(provinceRegionId != null, School::getProvinceRegionId, provinceRegionId)
                .eq(cityRegionId != null, School::getCityRegionId, cityRegionId)
                .eq(countyRegionId != null, School::getCountyRegionId, countyRegionId)
                .eq(townshipRegionId != null, School::getTownshipRegionId, townshipRegionId)
                .orderByDesc(School::getCreatedAt);

        if (StringUtils.hasText(keyword)) {
            String cleanKeyword = keyword.trim();
            wrapper.and(item -> item.like(School::getSchoolName, cleanKeyword)
                    .or()
                    .like(School::getSchoolType, cleanKeyword)
                    .or()
                    .like(School::getAddress, cleanKeyword));
        }

        Page<School> page = page(new Page<>(safePageNum, safePageSize), wrapper);
        return PageResult.of(
                page.getRecords().stream().map(this::toSchoolAdminVO).toList(),
                page.getTotal(),
                safePageNum,
                safePageSize
        );
    }

    @Override
    @Transactional
    public SchoolImportResultVO importCsv(SchoolCsvImportRequest request) {
        if (request == null || !StringUtils.hasText(request.getCsvContent())) {
            throw new IllegalArgumentException("csvContent is required");
        }
        SchoolImportResultVO result = new SchoolImportResultVO();
        String[] lines = request.getCsvContent().replace("\r", "").split("\n");
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.isEmpty() || (index == 0 && line.toLowerCase().startsWith("schoolcode,"))) continue;
            try {
                String[] columns = line.split(",", -1);
                if (columns.length < 6 || !StringUtils.hasText(columns[0]) || !StringUtils.hasText(columns[1])) {
                    throw new IllegalArgumentException("需要 schoolCode,schoolName,schoolType,address,longitude,latitude 六列");
                }
                String code = columns[0].trim();
                School school = getOne(new LambdaQueryWrapper<School>().eq(School::getSchoolCode, code).last("LIMIT 1"));
                boolean created = school == null;
                if (created) { school = new School(); school.setSchoolCode(code); }
                school.setSchoolName(columns[1].trim());
                school.setSchoolType(emptyToNull(columns[2]));
                school.setAddress(emptyToNull(columns[3]));
                school.setLongitude(new BigDecimal(columns[4].trim()));
                school.setLatitude(new BigDecimal(columns[5].trim()));
                school.setReviewStatus("approved");
                school.setActive(true);
                if (created) { save(school); result.setCreatedCount(result.getCreatedCount() + 1); }
                else { updateById(school); result.setUpdatedCount(result.getUpdatedCount() + 1); }
            } catch (RuntimeException exception) {
                result.setFailedCount(result.getFailedCount() + 1);
                result.getErrors().add(new SchoolImportErrorVO(index + 1, null, exception.getMessage()));
            }
        }
        return result;
    }

    @Override
    public SchoolImportResultVO importExcel(MultipartFile file) {
        validateImportFile(file);
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
                throw new IllegalArgumentException("Excel 文件没有可导入的数据");
            }
            Map<String, Integer> headerIndexes = readHeaderIndexes(sheet.getRow(sheet.getFirstRowNum()));
            SchoolImportResultVO result = new SchoolImportResultVO();
            Map<String, Integer> importedCodes = new HashMap<>();
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (isBlankRow(row, formatter)) continue;
                int excelRowNumber = rowIndex + 1;
                String schoolCode = readCell(row, headerIndexes, "school_code", formatter);
                try {
                    String normalizedCode = required(schoolCode, "school_code");
                    Integer firstRow = importedCodes.putIfAbsent(normalizedCode, excelRowNumber);
                    if (firstRow != null) {
                        throw new IllegalArgumentException("school_code 已在本文件第 " + firstRow + " 行出现");
                    }
                    School school = getOne(new LambdaQueryWrapper<School>()
                            .eq(School::getSchoolCode, normalizedCode).last("LIMIT 1"));
                    if (school == null) {
                        school = buildSchool(row, headerIndexes, formatter, new School());
                        save(school);
                        result.setCreatedCount(result.getCreatedCount() + 1);
                    } else {
                        buildSchool(row, headerIndexes, formatter, school);
                        updateById(school);
                        result.setUpdatedCount(result.getUpdatedCount() + 1);
                    }
                } catch (Exception exception) {
                    result.setFailedCount(result.getFailedCount() + 1);
                    result.getErrors().add(new SchoolImportErrorVO(excelRowNumber, emptyToNull(schoolCode), readableMessage(exception)));
                }
            }
            return result;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("无法读取 Excel 文件：" + readableMessage(exception));
        }
    }

    @Override
    public byte[] buildImportTemplate() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("学校导入");
            Row header = sheet.createRow(0);
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.DARK_GREEN.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            CreationHelper creationHelper = workbook.getCreationHelper();
            Drawing<?> drawing = sheet.createDrawingPatriarch();
            for (int index = 0; index < IMPORT_HEADERS.size(); index++) {
                Cell cell = header.createCell(index);
                cell.setCellValue(IMPORT_HEADERS.get(index));
                cell.setCellStyle(headerStyle);
                Comment comment = drawing.createCellComment(creationHelper.createClientAnchor());
                comment.setString(creationHelper.createRichTextString(templateHint(IMPORT_HEADERS.get(index))));
                cell.setCellComment(comment);
                sheet.setColumnWidth(index, index == 12 ? 32 * 256 : 18 * 256);
            }
            Row example = sheet.createRow(1);
            List<String> values = List.of("SCH001", "示例小学", "primary_school", "primary", "public", "示例路 1 号",
                    "114.500000", "38.000000", "河北省", "石家庄市", "示例区", "示例镇", "用于填写学校简介", "0311-0000000", "张老师");
            for (int index = 0; index < values.size(); index++) example.createCell(index).setCellValue(values.get(index));
            sheet.createFreezePane(0, 1);
            addListValidation(sheet, 3, Arrays.stream(SchoolLevel.values()).map(SchoolLevel::getValue).toArray(String[]::new));
            addListValidation(sheet, 4, Arrays.stream(SchoolNature.values()).map(SchoolNature::getValue).toArray(String[]::new));
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("生成学校导入模板失败", exception);
        }
    }

    private School buildSchool(Row row, Map<String, Integer> headerIndexes, DataFormatter formatter, School school) {
        String schoolCode = validateLength(required(readCell(row, headerIndexes, "school_code", formatter), "school_code"), "school_code", 50);
        String schoolName = validateLength(required(readCell(row, headerIndexes, "school_name", formatter), "school_name"), "school_name", 200);
        BigDecimal longitude = parseCoordinate(readCell(row, headerIndexes, "longitude", formatter), "经度", new BigDecimal("-180"), new BigDecimal("180"));
        BigDecimal latitude = parseCoordinate(readCell(row, headerIndexes, "latitude", formatter), "纬度", new BigDecimal("-90"), new BigDecimal("90"));
        RegionPath regions = resolveRegions(
                readCell(row, headerIndexes, "province_name", formatter), readCell(row, headerIndexes, "city_name", formatter),
                readCell(row, headerIndexes, "county_name", formatter), readCell(row, headerIndexes, "township_name", formatter));
        school.setSchoolCode(schoolCode);
        school.setSchoolName(schoolName);
        school.setSchoolType(validateLength(emptyToNull(readCell(row, headerIndexes, "school_type", formatter)), "school_type", 100));
        school.setSchoolLevel(valueOrDefault(parseSchoolLevel(readCell(row, headerIndexes, "school_level", formatter)),
                school.getSchoolLevel(), "primary"));
        school.setSchoolNature(valueOrDefault(parseSchoolNature(readCell(row, headerIndexes, "school_nature", formatter)),
                school.getSchoolNature(), "public"));
        school.setAddress(validateLength(emptyToNull(readCell(row, headerIndexes, "address", formatter)), "address", 300));
        school.setLongitude(longitude);
        school.setLatitude(latitude);
        school.setProvinceRegionId(regions.provinceId());
        school.setCityRegionId(regions.cityId());
        school.setCountyRegionId(regions.countyId());
        school.setTownshipRegionId(regions.townshipId());
        school.setIntro(validateLength(emptyToNull(readCell(row, headerIndexes, "intro", formatter)), "intro", 5000));
        school.setContactPhone(validateLength(emptyToNull(readCell(row, headerIndexes, "contact_phone", formatter)), "contact_phone", 50));
        school.setPrincipalName(validateLength(emptyToNull(readCell(row, headerIndexes, "principal_name", formatter)), "principal_name", 100));
        school.setReviewStatus("approved");
        school.setActive(true);
        return school;
    }

    private RegionPath resolveRegions(String provinceName, String cityName, String countyName, String townshipName) {
        String county = required(countyName, "county_name");
        AdministrativeRegion province = resolveOptional(RegionLevel.PROVINCE, provinceName, null, "省份");
        AdministrativeRegion city = resolveOptional(RegionLevel.CITY, cityName, province == null ? null : province.getRegionId(), "城市");
        AdministrativeRegion countyRegion = resolveCounty(county, city == null ? null : city.getRegionId());
        if (city != null && !city.getRegionId().equals(countyRegion.getParentRegionId())) {
            throw new IllegalArgumentException("区县“" + county + "”不属于城市“" + cityName.trim() + "”");
        }
        if (city == null) city = parentOf(countyRegion, RegionLevel.CITY, "区县未关联有效城市");
        if (province != null && !province.getRegionId().equals(city.getParentRegionId())) {
            throw new IllegalArgumentException("城市“" + city.getRegionName() + "”不属于省份“" + provinceName.trim() + "”");
        }
        if (province == null) province = parentOf(city, RegionLevel.PROVINCE, "城市未关联有效省份");
        AdministrativeRegion township = resolveOptional(RegionLevel.TOWNSHIP, townshipName, countyRegion.getRegionId(), "乡镇");
        return new RegionPath(province.getRegionId(), city.getRegionId(), countyRegion.getRegionId(), township == null ? null : township.getRegionId());
    }

    private AdministrativeRegion resolveCounty(String countyName, Long cityId) {
        List<AdministrativeRegion> candidates = administrativeRegionMapper.selectList(new LambdaQueryWrapper<AdministrativeRegion>()
                .eq(AdministrativeRegion::getRegionLevel, RegionLevel.COUNTY)
                .eq(cityId != null, AdministrativeRegion::getParentRegionId, cityId));
        return singleNameMatch(candidates, countyName, "区县");
    }

    private AdministrativeRegion resolveOptional(RegionLevel level, String value, Long parentId, String label) {
        if (!StringUtils.hasText(value)) return null;
        List<AdministrativeRegion> candidates = administrativeRegionMapper.selectList(new LambdaQueryWrapper<AdministrativeRegion>()
                .eq(AdministrativeRegion::getRegionLevel, level)
                .eq(parentId != null, AdministrativeRegion::getParentRegionId, parentId));
        return singleNameMatch(candidates, value, label);
    }

    private AdministrativeRegion singleNameMatch(List<AdministrativeRegion> candidates, String requestedName, String label) {
        String normalized = normalizeRegionName(requestedName);
        List<AdministrativeRegion> matches = candidates.stream().filter(item -> normalizeRegionName(item.getRegionName()).equals(normalized)).toList();
        if (matches.isEmpty()) throw new IllegalArgumentException("无法找到" + label + "“" + clean(requestedName) + "”");
        if (matches.size() > 1) throw new IllegalArgumentException(label + "“" + clean(requestedName) + "”存在歧义，请补充上级行政区");
        return matches.getFirst();
    }

    private AdministrativeRegion parentOf(AdministrativeRegion child, RegionLevel expectedLevel, String message) {
        if (child == null || child.getParentRegionId() == null) throw new IllegalArgumentException(message);
        AdministrativeRegion parent = administrativeRegionMapper.selectById(child.getParentRegionId());
        if (parent == null || parent.getRegionLevel() != expectedLevel) throw new IllegalArgumentException(message);
        return parent;
    }

    private void validateImportFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择非空的 .xlsx 文件");
        if (file.getSize() > MAX_IMPORT_BYTES) throw new IllegalArgumentException("Excel 文件不能超过 10 MB");
        String filename = file.getOriginalFilename();
        if (!StringUtils.hasText(filename) || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IllegalArgumentException("仅支持 .xlsx 格式的 Excel 文件");
        }
    }

    private Map<String, Integer> readHeaderIndexes(Row headerRow) {
        if (headerRow == null) throw new IllegalArgumentException("Excel 缺少表头");
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        Map<String, Integer> indexes = new HashMap<>();
        for (Cell cell : headerRow) {
            String value = clean(formatter.formatCellValue(cell));
            if (StringUtils.hasText(value)) indexes.put(value, cell.getColumnIndex());
        }
        List<String> missing = IMPORT_HEADERS.stream().filter(header -> !indexes.containsKey(header)).toList();
        if (!missing.isEmpty()) throw new IllegalArgumentException("Excel 表头不匹配，缺少：" + String.join("、", missing));
        return indexes;
    }

    private void addListValidation(Sheet sheet, int column, String[] values) {
        DataValidationConstraint constraint = sheet.getDataValidationHelper().createExplicitListConstraint(values);
        DataValidation validation = sheet.getDataValidationHelper().createValidation(constraint, new CellRangeAddressList(1, 1000, column, column));
        validation.setSuppressDropDownArrow(true);
        sheet.addValidationData(validation);
    }

    private String templateHint(String header) {
        return switch (header) {
            case "school_code" -> "必填，唯一编码，用于新增或更新学校。";
            case "school_name" -> "必填，最长 200 个字符。";
            case "longitude" -> "必填，经度范围 -180 到 180。";
            case "latitude" -> "必填，纬度范围 -90 到 90。";
            case "county_name" -> "必填；省、市、区县按父子关系解析。";
            case "township_name" -> "可选；填写时必须属于对应区县。";
            case "school_level" -> "可选，使用下拉选项中的英文值。";
            case "school_nature" -> "可选，使用下拉选项中的英文值。";
            default -> "可选字段；请使用文本填写。";
        };
    }

    private boolean isBlankRow(Row row, DataFormatter formatter) {
        if (row == null) return true;
        for (Cell cell : row) if (StringUtils.hasText(clean(formatter.formatCellValue(cell)))) return false;
        return true;
    }

    private String readCell(Row row, Map<String, Integer> indexes, String header, DataFormatter formatter) {
        Integer columnIndex = indexes.get(header);
        Cell cell = columnIndex == null ? null : row.getCell(columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell == null ? null : clean(formatter.formatCellValue(cell));
    }

    private BigDecimal parseCoordinate(String value, String label, BigDecimal minimum, BigDecimal maximum) {
        try {
            BigDecimal coordinate = new BigDecimal(required(value, label));
            if (coordinate.compareTo(minimum) < 0 || coordinate.compareTo(maximum) > 0) {
                throw new IllegalArgumentException(label + "必须在 " + minimum.stripTrailingZeros().toPlainString() + " 到 " + maximum.stripTrailingZeros().toPlainString() + " 之间");
            }
            return coordinate;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + "必须是有效数字");
        }
    }

    private String parseSchoolLevel(String value) {
        if (!StringUtils.hasText(value)) return null;
        return SchoolLevel.fromValue(value).getValue();
    }

    private String parseSchoolNature(String value) {
        if (!StringUtils.hasText(value)) return null;
        return SchoolNature.fromValue(value).getValue();
    }

    private String valueOrDefault(String importedValue, String existingValue, String defaultValue) {
        if (StringUtils.hasText(importedValue)) return importedValue;
        return StringUtils.hasText(existingValue) ? existingValue : defaultValue;
    }

    private String required(String value, String field) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException(field + " 为必填项");
        return value.trim();
    }

    private String validateLength(String value, String field, int maxLength) {
        if (value != null && value.length() > maxLength) throw new IllegalArgumentException(field + " 长度不能超过 " + maxLength);
        return value;
    }

    private String normalizeRegionName(String value) {
        String normalized = clean(value);
        if (normalized == null) return "";
        normalized = normalized.replaceAll("[\\s　]+", "");
        return normalized.replaceFirst("(特别行政区|自治区|省|市|区|县|镇|乡|街道)$", "");
    }

    private String readableMessage(Exception exception) {
        return StringUtils.hasText(exception.getMessage()) ? exception.getMessage() : "导入失败";
    }

    private record RegionPath(Long provinceId, Long cityId, Long countyId, Long townshipId) { }

    private void validateCreateRequest(SchoolCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        if (!StringUtils.hasText(request.getSchoolName())) {
            throw new IllegalArgumentException("schoolName is required");
        }
    }

    private School requireSchool(Long schoolId) {
        if (schoolId == null) {
            throw new IllegalArgumentException("schoolId is required");
        }
        School school = getById(schoolId);
        if (school == null) {
            throw new IllegalArgumentException("school not found");
        }
        return school;
    }

    private void fillSchoolForCreate(School school, SchoolCreateRequest request) {
        school.setSchoolName(clean(request.getSchoolName()));
        school.setProvinceRegionId(request.getProvinceRegionId());
        school.setCityRegionId(request.getCityRegionId());
        school.setCountyRegionId(request.getCountyRegionId());
        school.setTownshipRegionId(request.getTownshipRegionId());
        school.setSchoolType(clean(request.getSchoolType()));
        school.setAddress(clean(request.getAddress()));
        school.setContactPhone(clean(request.getContactPhone()));
        school.setPrincipalName(clean(request.getPrincipalName()));
        school.setLongitude(request.getLongitude());
        school.setLatitude(request.getLatitude());
        school.setIntro(clean(request.getIntro()));
    }

    private void fillSchoolForUpdate(School school, SchoolUpdateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        school.setSchoolName(valueOrOriginal(request.getSchoolName(), school.getSchoolName()));
        school.setProvinceRegionId(valueOrOriginal(request.getProvinceRegionId(), school.getProvinceRegionId()));
        school.setCityRegionId(valueOrOriginal(request.getCityRegionId(), school.getCityRegionId()));
        school.setCountyRegionId(valueOrOriginal(request.getCountyRegionId(), school.getCountyRegionId()));
        school.setTownshipRegionId(valueOrOriginal(request.getTownshipRegionId(), school.getTownshipRegionId()));
        school.setSchoolType(valueOrOriginal(request.getSchoolType(), school.getSchoolType()));
        school.setAddress(valueOrOriginal(request.getAddress(), school.getAddress()));
        school.setContactPhone(valueOrOriginal(request.getContactPhone(), school.getContactPhone()));
        school.setPrincipalName(valueOrOriginal(request.getPrincipalName(), school.getPrincipalName()));
        school.setLongitude(valueOrOriginal(request.getLongitude(), school.getLongitude()));
        school.setLatitude(valueOrOriginal(request.getLatitude(), school.getLatitude()));
        school.setIntro(valueOrOriginal(request.getIntro(), school.getIntro()));
        school.setActive(valueOrOriginal(request.getActive(), school.getActive()));
    }

    private SchoolAdminVO toSchoolAdminVO(School school) {
        SchoolAdminVO vo = new SchoolAdminVO();
        vo.setSchoolId(school.getSchoolId());
        vo.setSchoolCode(school.getSchoolCode());
        vo.setSchoolName(school.getSchoolName());
        vo.setProvinceRegionId(school.getProvinceRegionId());
        vo.setCityRegionId(school.getCityRegionId());
        vo.setCountyRegionId(school.getCountyRegionId());
        vo.setTownshipRegionId(school.getTownshipRegionId());
        vo.setSchoolType(school.getSchoolType());
        vo.setSchoolLevel(school.getSchoolLevel());
        vo.setSchoolNature(school.getSchoolNature());
        vo.setAddress(school.getAddress());
        vo.setContactPhone(school.getContactPhone());
        vo.setPrincipalName(school.getPrincipalName());
        vo.setLongitude(school.getLongitude());
        vo.setLatitude(school.getLatitude());
        vo.setIntro(school.getIntro());
        vo.setActive(school.getActive());
        vo.setCreatedAt(school.getCreatedAt());
        vo.setUpdatedAt(school.getUpdatedAt());
        return vo;
    }

    private String clean(String value) {
        return value == null ? null : value.trim();
    }

    private String emptyToNull(String value) {
        String result = clean(value);
        return StringUtils.hasText(result) ? result : null;
    }

    private <T> T valueOrOriginal(T newValue, T originalValue) {
        return newValue == null ? originalValue : newValue;
    }

    private String valueOrOriginal(String newValue, String originalValue) {
        return newValue == null ? originalValue : clean(newValue);
    }
}
