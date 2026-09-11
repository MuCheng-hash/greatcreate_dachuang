package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.OperationType;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 审计日志实体，用于持久化对应业务数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "audit_log", autoResultMap = true)
public class AuditLog extends BaseAuditEntity {

    /**
     * 审计日志标识。
     */
    @TableId(value = "log_id", type = IdType.AUTO)
    private Long logId;

    /**
     * 业务实体类型。
     */
    @TableField("entity_type")
    private String entityType;

    /**
     * 关联的业务实体标识。
     */
    @TableField("entity_id")
    private Long entityId;

    /**
     * 审计操作类型。
     */
    @TableField("operation_type")
    private OperationType operationType;

    /**
     * 操作人名称。
     */
    @TableField("operator_name")
    private String operatorName;

    /**
     * 本次操作的变更摘要。
     */
    @TableField("change_summary")
    private String changeSummary;
}
