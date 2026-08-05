package com.redculture.platform.vo.request;

import lombok.Data;

@Data
public class UserAccountResetPasswordRequest {
    private String password;
    private Boolean forcePasswordChange;
}
