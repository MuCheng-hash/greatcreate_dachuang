package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.Gender;
import com.redculture.platform.enums.ReviewStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 英雄人物实体，用于持久化对应业务数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "hero_person", autoResultMap = true)
public class HeroPerson extends BaseAuditEntity {

    /**
     * 英雄人物标识。
     */
    @TableId(value = "hero_id", type = IdType.AUTO)
    private Long heroId;

    /**
     * 英雄人物业务编码。
     */
    @TableField("hero_code")
    private String heroCode;

    /**
     * 人物姓名。
     */
    @TableField("hero_name")
    private String heroName;

    /**
     * 性别。
     */
    @TableField("gender")
    private Gender gender;

    /**
     * 出生年份。
     */
    @TableField("birth_year")
    private Integer birthYear;

    /**
     * 去世年份。
     */
    @TableField("death_year")
    private Integer deathYear;

    /**
     * 出生日期文字说明。
     */
    @TableField("birth_date_text")
    private String birthDateText;

    /**
     * 去世日期文字说明。
     */
    @TableField("death_date_text")
    private String deathDateText;

    /**
     * 关联的籍贯行政区划标识。
     */
    @TableField("native_place_region_id")
    private Long nativePlaceRegionId;

    /**
     * 籍贯文字说明。
     */
    @TableField("native_place_text")
    private String nativePlaceText;

    /**
     * 资料摘要。
     */
    @TableField("profile_summary")
    private String profileSummary;

    /**
     * 主要事迹。
     */
    @TableField("main_deeds")
    private String mainDeeds;

    /**
     * 官方网站地址。
     */
    @TableField("official_url")
    private String officialUrl;

    /**
     * 内容审核状态，取值由 {@link com.redculture.platform.enums.ReviewStatus} 定义。
     */
    @TableField("review_status")
    private ReviewStatus reviewStatus;

    /**
     * 是否启用。
     */
    @TableField("is_active")
    private Boolean active;
}
