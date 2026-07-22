package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("resource_discovery_run_item")
public class ResourceDiscoveryRunItem implements Serializable {

    @TableField("run_id")
    private Long runId;

    @TableField("candidate_id")
    private Long candidateId;

    @TableField("result_rank")
    private Integer resultRank;

    @TableField("distance_meters")
    private Integer distanceMeters;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
