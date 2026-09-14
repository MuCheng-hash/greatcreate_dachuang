package com.redculture.platform.vo.request;

import lombok.Data;

/** 学校CSV导入请求参数。 */
@Data
public class SchoolCsvImportRequest {
    /** UTF-8 CSV 文本，列顺序为：schoolCode、schoolName、schoolType、address、longitude、latitude。 */
    private String csvContent;
}
