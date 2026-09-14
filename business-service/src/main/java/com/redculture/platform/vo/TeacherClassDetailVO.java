package com.redculture.platform.vo;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/** 教师班级详情视图对象。 */
@Data
public class TeacherClassDetailVO extends TeacherClassVO {
    /** 当前用户是否可以管理班级学生。 */
    private boolean canManageStudents;
    /** 学生列表。 */
    private List<ClassStudentVO> students = new ArrayList<>();
    /** 任务列表。 */
    private List<ClassTaskVO> tasks = new ArrayList<>();
}
