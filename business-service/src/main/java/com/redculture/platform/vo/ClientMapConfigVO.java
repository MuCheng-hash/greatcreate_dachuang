package com.redculture.platform.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ClientMapConfigVO {

    private String amapKey;

    private String amapSecurityJsCode;

    private String llmServiceBaseUrl;
}
