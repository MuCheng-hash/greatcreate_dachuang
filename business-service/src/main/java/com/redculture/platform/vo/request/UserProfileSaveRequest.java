package com.redculture.platform.vo.request;

import lombok.Data;

import java.util.List;

@Data
public class UserProfileSaveRequest {
    private Long accountId;
    private String profileType;
    private String realName;
    private String gender;
    private String phone;
    private String email;
    private Long schoolId;
    private String status;
    private String remark;
    private String teacherNo;
    private String title;
    private String studentNo;
    private String gradeName;
    private Integer enrollmentYear;
    private List<Long> classIds;
    private String teacherClassRole;
}
