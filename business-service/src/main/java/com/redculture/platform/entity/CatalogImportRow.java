package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 目录数据导入行实体，对应数据库表 {@code catalog_import_row}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("catalog_import_row")
public class CatalogImportRow extends BaseAuditEntity {

    /**
     * 目录数据导入行标识。
     */
    @TableId(value = "row_id", type = IdType.AUTO)
    private Long rowId;

    /**
     * 关联的导入批次标识。
     */
    @TableField("batch_id")
    private Long batchId;

    /**
     * 工作表名称。
     */
    @TableField("sheet_name")
    private String sheetName;

    /**
     * 源文件中的行号。
     */
    @TableField("row_no")
    private Integer rowNumber;

    /**
     * 业务实体类型。
     */
    @TableField("entity_type")
    private String entityType;

    /**
     * JSON 格式的payload数据。
     */
    @TableField("payload_json")
    private String payloadJson;

    /**
     * 导入行校验状态；校验失败时为 {@code FAILED}。
     */
    @TableField("validation_status")
    private String validationStatus;

    /**
     * 该行的校验结果说明。
     */
    @TableField("validation_message")
    private String validationMessage;

    /**
     * 关联的导入后的业务实体标识。
     */
    @TableField("imported_entity_id")
    private Long importedEntityId;
}
