package com.redculture.platform.vo;

import lombok.Data;

@Data
public class ClassInfoAdminVO {
    private Long classId;
    private Long schoolId;
    private String schoolName;
    private String className;
    private String gradeName;
    private String classType;
    private String status;
}
