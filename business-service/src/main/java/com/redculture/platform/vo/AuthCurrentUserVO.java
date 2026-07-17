package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;

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
}
