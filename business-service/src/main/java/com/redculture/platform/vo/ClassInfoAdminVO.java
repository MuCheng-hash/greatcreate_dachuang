package com.redculture.platform.vo;

import lombok.Data;

/** 班级信息管理端视图对象。 */
@Data
public class ClassInfoAdminVO {
    /** 班级标识。 */
    private Long classId;
    /** 学校标识。 */
    private Long schoolId;
    /** 学校名称。 */
    private String schoolName;
    /** 班级名称。 */
    private String className;
    /** 年级名称。 */
    private String gradeName;
    /**
     * 班级类型。
     * 具体取值由对应业务流程或协议定义。
     */
    private String classType;
    /**
     * 当前对象的业务状态。
     * 具体取值由所属业务流程定义；为空表示尚未提供状态。
     */
    private String status;
}
