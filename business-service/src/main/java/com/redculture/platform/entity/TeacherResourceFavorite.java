package com.redculture.platform.entity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
/**
 * 教师收藏资源关系标识。
 */
@Data @EqualsAndHashCode(callSuper = true) @TableName("teacher_resource_favorite")
public class TeacherResourceFavorite extends BaseAuditEntity { /** 教师收藏记录标识。 */ @TableId(value="favorite_id", type=IdType.AUTO) private Long favoriteId; /** 关联的教师标识。 */ @TableField("teacher_id") private Long teacherId; /** 关联的教育资源标识。 */ @TableField("resource_id") private Long resourceId; }
