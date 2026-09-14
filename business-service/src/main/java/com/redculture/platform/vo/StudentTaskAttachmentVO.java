package com.redculture.platform.vo;
import lombok.Data;
/** 学生任务附件视图对象。 */
@Data public class StudentTaskAttachmentVO { /** 附件标识。 */ private Long attachmentId; /** 附件上传时的原始文件名。 */ private String originalFilename; /** 附件内容类型。 */ private String contentType; /** 文件大小，单位为字节。 */ private Long fileSize; }
