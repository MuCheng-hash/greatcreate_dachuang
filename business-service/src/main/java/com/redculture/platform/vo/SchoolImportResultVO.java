package com.redculture.platform.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 学校导入结果视图对象。 */
@Data
public class SchoolImportResultVO {
    /** 创建数量。 */
    private int createdCount;
    /** 更新数量。 */
    private int updatedCount;
    /** 失败数量。 */
    private int failedCount;
    /** 错误列表。 */
    private List<SchoolImportErrorVO> errors = new ArrayList<>();
}
