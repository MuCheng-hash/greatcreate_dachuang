package com.redculture.platform.vo.request;

import lombok.Data;

/** 邀请码加入请求参数。 */
@Data
public class InviteJoinRequest {
    /** 用于加入或注册的邀请编码。 */
    private String inviteCode;
}
