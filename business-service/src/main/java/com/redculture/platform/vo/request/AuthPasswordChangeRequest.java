package com.redculture.platform.vo.request;

import lombok.Data;

@Data
public class AuthPasswordChangeRequest {

    private String currentPassword;

    private String newPassword;
}
