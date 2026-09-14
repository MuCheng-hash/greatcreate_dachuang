package com.redculture.platform.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 教师注册邀请码视图对象。 */
@Data
public class TeacherRegistrationInviteVO {
    /** 邀请码标识。 */
    private Long inviteId;
    /** 学校标识。 */
    private Long schoolId;
    /**
     * 当前对象的业务状态。
     * 具体取值由所属业务流程定义；为空表示尚未提供状态。
     */
    private String status;
    /** 过期时间。 */
    private LocalDateTime expiresAt;
    /** 邀请码允许使用的最大次数。 */
    private Integer maxUses;
    /** 邀请码已经使用的次数。 */
    private Integer usedCount;
    /** 创建者账号标识。 */
    private Long createdByAccountId;
    /** 创建时间。 */
    private LocalDateTime createdAt;
    /** 邀请创建成功时仅返回一次的明文邀请码。 */
    private String inviteCode;
}
