package com.redculture.platform.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class UserProfileAdminVO {
    private Long profileId;
    private Long accountId;
    private String username;
    private String profileType;
    private String realName;
    private String gender;
    private String phone;
    private String email;
    private Long schoolId;
    private String schoolName;
    private String status;
    private String remark;
    private Long teacherId;
    private String teacherNo;
    private String title;
    private Long studentId;
    private String studentNo;
    private String gradeName;
    private Integer enrollmentYear;
    private List<Long> classIds = new ArrayList<>();
    private List<String> classNames = new ArrayList<>();
    private LocalDateTime createdAt;
}
