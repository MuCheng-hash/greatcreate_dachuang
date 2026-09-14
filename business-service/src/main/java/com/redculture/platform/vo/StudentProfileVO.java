package com.redculture.platform.vo;

import lombok.Data;

/** 学生资料视图对象。 */
@Data
public class StudentProfileVO {
    /** 学生标识。 */
    private Long studentId;
    /** 学生名称。 */
    private String studentName;
    /** 学生编号。 */
    private String studentNo;
    /** 年级名称。 */
    private String gradeName;
}
