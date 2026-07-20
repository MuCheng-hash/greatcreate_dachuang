package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.DiscoveryRunStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "resource_discovery_run", autoResultMap = true)
public class ResourceDiscoveryRun extends BaseAuditEntity {

    @TableId(value = "run_id", type = IdType.AUTO)
    private Long runId;

    @TableField("school_id")
    private Long schoolId;

    @TableField("radius_meters")
    private Integer radiusMeters;

    @TableField("provider")
    private String provider;

    @TableField("status")
    private DiscoveryRunStatus status;

    @TableField("forced")
    private Boolean forced;

    @TableField("provider_count")
    private Integer providerCount;

    @TableField("candidate_count")
    private Integer candidateCount;

    @TableField("analysis_count")
    private Integer analysisCount;

    @TableField("error_message")
    private String errorMessage;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    @TableField("cache_expires_at")
    private LocalDateTime cacheExpiresAt;
}
