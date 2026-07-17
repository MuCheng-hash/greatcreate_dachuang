package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.ReviewStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "memorial_hall", autoResultMap = true)
public class MemorialHall extends BaseAuditEntity {

    @TableId(value = "memorial_id", type = IdType.AUTO)
    private Long memorialId;

    @TableField("memorial_code")
    private String memorialCode;

    @TableField("memorial_name")
    private String memorialName;

    @TableField("region_id")
    private Long regionId;

    @TableField("address")
    private String address;

    @TableField("longitude")
    private BigDecimal longitude;

    @TableField("latitude")
    private BigDecimal latitude;

    @TableField("exhibition_content")
    private String exhibitionContent;

    @TableField("intro")
    private String intro;

    @TableField("opening_time_desc")
    private String openingTimeDesc;

    @TableField("ticket_info")
    private String ticketInfo;

    @TableField("contact_phone")
    private String contactPhone;

    @TableField("official_url")
    private String officialUrl;

    @TableField("review_status")
    private ReviewStatus reviewStatus;

    @TableField("is_active")
    private Boolean active;
}
