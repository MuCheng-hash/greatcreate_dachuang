package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.Gender;
import com.redculture.platform.enums.ReviewStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "hero_person", autoResultMap = true)
public class HeroPerson extends BaseAuditEntity {

    @TableId(value = "hero_id", type = IdType.AUTO)
    private Long heroId;

    @TableField("hero_code")
    private String heroCode;

    @TableField("hero_name")
    private String heroName;

    @TableField("hero_alias")
    private String heroAlias;

    @TableField("gender")
    private Gender gender;

    @TableField("birth_year")
    private Integer birthYear;

    @TableField("death_year")
    private Integer deathYear;

    @TableField("birth_date_text")
    private String birthDateText;

    @TableField("death_date_text")
    private String deathDateText;

    @TableField("native_place_region_id")
    private Long nativePlaceRegionId;

    @TableField("native_place_text")
    private String nativePlaceText;

    @TableField("profile_summary")
    private String profileSummary;

    @TableField("main_deeds")
    private String mainDeeds;

    @TableField("official_url")
    private String officialUrl;

    @TableField("review_status")
    private ReviewStatus reviewStatus;

    @TableField("is_active")
    private Boolean active;
}
