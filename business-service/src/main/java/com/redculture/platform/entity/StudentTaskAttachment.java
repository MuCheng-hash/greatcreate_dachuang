package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("student_task_attachment")
public class StudentTaskAttachment extends BaseAuditEntity {
    @TableId(value = "attachment_id", type = IdType.AUTO) private Long attachmentId;
    @TableField("submission_id") private Long submissionId;
    @TableField("original_filename") private String originalFilename;
    @TableField("storage_key") private String storageKey;
    @TableField("content_type") private String contentType;
    @TableField("file_size") private Long fileSize;
}
