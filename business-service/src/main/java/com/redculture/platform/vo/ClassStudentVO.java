package com.redculture.platform.vo;

import lombok.Data;

/** 班级学生视图对象。 */
@Data
public class ClassStudentVO {
    /** 学生标识。 */
    private Long studentId;
    /** 学生编号。 */
    private String studentNo;
    /** 学生名称。 */
    private String studentName;
    /** 年级名称。 */
    private String gradeName;
    /**
     * 成员状态。
     * 具体取值由对应业务流程或协议定义。
     */
    private String memberStatus;
}
