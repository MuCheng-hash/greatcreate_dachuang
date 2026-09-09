package com.redculture.platform.vo.request;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/** 教师班级保存请求参数。 */
@Data
public class TeacherClassSaveRequest {
    /** 学校标识。 */
    private Long schoolId;
    /** 班级名称。 */
    private String className;
    /** 年级名称。 */
    private String gradeName;
    /**
     * 班级类型。
     * 具体取值由对应业务流程或协议定义。
     */
    private String classType;
    /** 班主任教师标识。 */
    private Long headTeacherId;
    /** 科目教师标识列表。 */
    private List<Long> subjectTeacherIds = new ArrayList<>();
}
