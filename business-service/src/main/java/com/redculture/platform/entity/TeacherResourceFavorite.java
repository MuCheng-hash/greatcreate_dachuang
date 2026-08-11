package com.redculture.platform.entity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper = true) @TableName("teacher_resource_favorite")
public class TeacherResourceFavorite extends BaseAuditEntity { @TableId(value="favorite_id", type=IdType.AUTO) private Long favoriteId; @TableField("teacher_id") private Long teacherId; @TableField("resource_id") private Long resourceId; }
