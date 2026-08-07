package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("catalog_import_row")
public class CatalogImportRow extends BaseAuditEntity {

    @TableId(value = "row_id", type = IdType.AUTO)
    private Long rowId;

    @TableField("batch_id")
    private Long batchId;

    @TableField("sheet_name")
    private String sheetName;

    @TableField("row_no")
    private Integer rowNumber;

    @TableField("entity_type")
    private String entityType;

    @TableField("payload_json")
    private String payloadJson;

    @TableField("validation_status")
    private String validationStatus;

    @TableField("validation_message")
    private String validationMessage;

    @TableField("imported_entity_id")
    private Long importedEntityId;
}
