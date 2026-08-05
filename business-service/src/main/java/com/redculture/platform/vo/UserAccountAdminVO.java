package com.redculture.platform.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class UserAccountAdminVO {
    private Long accountId;
    private String username;
    private String displayName;
    private String realName;
    private String contactPhone;
    private String email;
    private String status;
    private Long schoolId;
    private String schoolName;
    private Long profileId;
    private String profileType;
    private List<String> roleCodes = new ArrayList<>();
    private List<String> roleNames = new ArrayList<>();
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
}
