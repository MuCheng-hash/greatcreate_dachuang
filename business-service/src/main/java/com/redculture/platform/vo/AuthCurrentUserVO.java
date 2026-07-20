package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class AuthCurrentUserVO {

    private Long accountId;

    private String username;

    private String roleCode;

    private Long schoolId;

    private String schoolName;

    private BigDecimal schoolLongitude;

    private BigDecimal schoolLatitude;

    private String displayName;

    private String contactName;

    private String contactPhone;

    private LocalDateTime lastLoginAt;
}
