package com.redculture.platform.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SchoolImportResultVO {
    private int createdCount;
    private int updatedCount;
    private int failedCount;
    private List<String> errors = new ArrayList<>();
}
