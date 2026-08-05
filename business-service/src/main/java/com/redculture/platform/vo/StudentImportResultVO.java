package com.redculture.platform.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class StudentImportResultVO {
    private int successCount;
    private int failedCount;
    private List<String> errors = new ArrayList<>();
}
