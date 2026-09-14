package com.redculture.platform.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 客户端地图功能所需的配置视图对象。 */
@Data
@AllArgsConstructor
public class ClientMapConfigVO {

    /** 高德地图 Web 服务 Key。 */
    private String amapKey;

    /** 高德地图 JS API 安全密钥。 */
    private String amapSecurityJsCode;
}
