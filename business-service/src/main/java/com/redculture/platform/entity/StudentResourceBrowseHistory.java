package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

/**
 * 学生资源浏览记录实体，对应数据库表 {@code student_resource_browse_history}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("student_resource_browse_history")
public class StudentResourceBrowseHistory extends BaseAuditEntity {
    /**
     * 学生资源浏览记录标识。
     */
    @TableId(value = "browse_id", type = IdType.AUTO) private Long browseId;
    /**
     * 关联的学生标识。
     */
    @TableField("student_id") private Long studentId;
    /**
     * 关联的教育资源标识。
     */
    @TableField("resource_id") private Long resourceId;
    /**
     * 最近浏览时间。
     */
    @TableField("viewed_at") private LocalDateTime viewedAt;
    /**
     * 累计浏览次数。
     */
    @TableField("view_count") private Integer viewCount;
}
