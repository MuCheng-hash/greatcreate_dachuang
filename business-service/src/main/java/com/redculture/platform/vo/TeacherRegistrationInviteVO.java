package com.redculture.platform.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TeacherRegistrationInviteVO {
    private Long inviteId;
    private Long schoolId;
    private String status;
    private LocalDateTime expiresAt;
    private Integer maxUses;
    private Integer usedCount;
    private Long createdByAccountId;
    private LocalDateTime createdAt;
    /** Returned only once when an invite is created. */
    private String inviteCode;
}
