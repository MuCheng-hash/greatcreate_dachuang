package com.redculture.platform.vo;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class TeacherClassVO {
    private Long classId;
    private Long schoolId;
    private String className;
    private String gradeName;
    private String classType;
    private String inviteCode;
    private String status;
    private boolean headTeacher;
    private long studentCount;
    private long activeTaskCount;
    private long completedTaskCount;
    private long overdueTaskCount;
    private double completionRate;
    private List<ClassTeacherVO> teachers = new ArrayList<>();
}
