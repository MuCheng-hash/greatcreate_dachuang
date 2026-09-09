package com.redculture.platform.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 学生导入结果视图对象。 */
@Data
public class StudentImportResultVO {
    /** 成功数量。 */
    private int successCount;
    /** 失败数量。 */
    private int failedCount;
    /** 错误列表。 */
    private List<String> errors = new ArrayList<>();
}
