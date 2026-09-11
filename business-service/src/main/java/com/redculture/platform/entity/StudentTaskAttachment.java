package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 学生任务提交附件实体，对应数据库表 {@code student_task_attachment}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("student_task_attachment")
public class StudentTaskAttachment extends BaseAuditEntity {
    /**
     * 学生任务提交附件标识。
     */
    @TableId(value = "attachment_id", type = IdType.AUTO) private Long attachmentId;
    /**
     * 关联的任务提交标识。
     */
    @TableField("submission_id") private Long submissionId;
    /**
     * 上传时的原始文件名。
     */
    @TableField("original_filename") private String originalFilename;
    /**
     * 对象存储键。
     */
    @TableField("storage_key") private String storageKey;
    /**
     * 内容类型。
     */
    @TableField("content_type") private String contentType;
    /**
     * 文件大小，单位字节。
     */
    @TableField("file_size") private Long fileSize;
}
