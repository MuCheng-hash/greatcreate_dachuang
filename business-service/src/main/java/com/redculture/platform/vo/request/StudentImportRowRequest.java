package com.redculture.platform.vo.request;

import lombok.Data;

@Data
public class StudentImportRowRequest {
    private String username;
    private String password;
    private String realName;
    private String studentNo;
    private Long schoolId;
    private Long classId;
    private String gradeName;
    private String phone;
    private String email;
    private Integer enrollmentYear;
}
