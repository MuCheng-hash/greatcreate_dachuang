package com.redculture.platform.vo;

import lombok.Data;

/** 班级教师视图对象。 */
@Data
public class ClassTeacherVO {
    /** 教师标识。 */
    private Long teacherId;
    /** 教师名称。 */
    private String teacherName;
    /** 教师角色。 */
    private String teacherRole;
}
