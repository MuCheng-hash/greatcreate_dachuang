package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 目录数据导入批次实体，对应数据库表 {@code catalog_import_batch}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("catalog_import_batch")
public class CatalogImportBatch extends BaseAuditEntity {

    /**
     * 目录数据导入批次标识。
     */
    @TableId(value = "batch_id", type = IdType.AUTO)
    private Long batchId;

    /**
     * 导入文件名称。
     */
    @TableField("file_name")
    private String fileName;

    /**
     * 创建者账号标识。
     */
    @TableField("created_by")
    private Long createdBy;

    /**
     * 导入批次状态；预览完成后为 {@code PREVIEWED}，提交后的状态由导入流程维护。
     */
    @TableField("status")
    private String status;

    /**
     * 总行数。
     */
    @TableField("total_rows")
    private Integer totalRows;

    /**
     * 校验通过的行数。
     */
    @TableField("valid_rows")
    private Integer validRows;

    /**
     * 无效行数。
     */
    @TableField("invalid_rows")
    private Integer invalidRows;

    /**
     * 重复数据行数。
     */
    @TableField("duplicate_rows")
    private Integer duplicateRows;
}
