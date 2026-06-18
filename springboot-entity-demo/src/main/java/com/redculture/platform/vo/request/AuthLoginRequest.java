package com.redculture.platform.vo.request;

import lombok.Data;

@Data
public class AuthLoginRequest {

    private String username;

    private String password;
}
