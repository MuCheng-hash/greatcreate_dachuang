package com.redculture.platform.vo;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class TeacherClassDetailVO extends TeacherClassVO {
    private boolean canManageStudents;
    private List<ClassStudentVO> students = new ArrayList<>();
    private List<ClassTaskVO> tasks = new ArrayList<>();
}
