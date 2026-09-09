package com.redculture.platform.vo.request;

import lombok.Data;

/** 用户账号状态请求参数。 */
@Data
public class UserAccountStatusRequest {
    /**
     * 当前对象的业务状态。
     * 具体取值由所属业务流程定义；为空表示尚未提供状态。
     */
    private String status;
}
