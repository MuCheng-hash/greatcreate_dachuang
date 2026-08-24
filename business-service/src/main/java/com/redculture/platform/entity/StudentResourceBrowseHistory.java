package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("student_resource_browse_history")
public class StudentResourceBrowseHistory extends BaseAuditEntity {
    @TableId(value = "browse_id", type = IdType.AUTO) private Long browseId;
    @TableField("student_id") private Long studentId;
    @TableField("resource_id") private Long resourceId;
    @TableField("viewed_at") private LocalDateTime viewedAt;
    @TableField("view_count") private Integer viewCount;
}
