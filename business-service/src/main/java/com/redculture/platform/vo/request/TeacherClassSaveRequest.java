package com.redculture.platform.vo.request;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class TeacherClassSaveRequest {
    private Long schoolId;
    private String className;
    private String gradeName;
    private String classType;
    private Long headTeacherId;
    private List<Long> subjectTeacherIds = new ArrayList<>();
}
