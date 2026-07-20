package com.redculture.platform.vo.request;

import lombok.Data;

@Data
public class AuthProfileUpdateRequest {

    private String displayName;

    private String contactName;

    private String contactPhone;
}
