package com.redculture.platform.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 学校导入错误视图对象。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SchoolImportErrorVO {
    /** 行编号。 */
    private int rowNumber;
    /** 学校名称。 */
    private String schoolName;
    /** 提示或响应消息。 */
    private String message;
}
