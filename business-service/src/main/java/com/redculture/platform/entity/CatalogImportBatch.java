package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("catalog_import_batch")
public class CatalogImportBatch extends BaseAuditEntity {

    @TableId(value = "batch_id", type = IdType.AUTO)
    private Long batchId;

    @TableField("file_name")
    private String fileName;

    @TableField("created_by")
    private Long createdBy;

    @TableField("status")
    private String status;

    @TableField("total_rows")
    private Integer totalRows;

    @TableField("valid_rows")
    private Integer validRows;

    @TableField("invalid_rows")
    private Integer invalidRows;

    @TableField("duplicate_rows")
    private Integer duplicateRows;
}
