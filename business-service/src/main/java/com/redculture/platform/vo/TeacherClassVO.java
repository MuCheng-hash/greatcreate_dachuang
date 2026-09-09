package com.redculture.platform.vo;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/** 教师班级视图对象。 */
@Data
public class TeacherClassVO {
    /** 班级标识。 */
    private Long classId;
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
    /** 用于加入或注册的邀请编码。 */
    private String inviteCode;
    /**
     * 当前对象的业务状态。
     * 具体取值由所属业务流程定义；为空表示尚未提供状态。
     */
    private String status;
    /** 当前教师是否为班主任。 */
    private boolean headTeacher;
    /** 学生数量。 */
    private long studentCount;
    /** 启用任务数量。 */
    private long activeTaskCount;
    /** 已完成任务数量。 */
    private long completedTaskCount;
    /** 逾期任务数量。 */
    private long overdueTaskCount;
    /** 完成比例。 */
    private double completionRate;
    /** 教师列表。 */
    private List<ClassTeacherVO> teachers = new ArrayList<>();
}
