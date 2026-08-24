package com.redculture.platform.vo.request;

import lombok.Data;

@Data
public class SchoolCsvImportRequest {
    /** UTF-8 CSV: schoolCode,schoolName,schoolType,address,longitude,latitude */
    private String csvContent;
}
