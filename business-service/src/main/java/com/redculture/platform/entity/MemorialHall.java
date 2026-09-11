package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.ReviewStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 纪念设施实体，用于持久化对应业务数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "memorial_hall", autoResultMap = true)
public class MemorialHall extends BaseAuditEntity {

    /**
     * 纪念设施标识。
     */
    @TableId(value = "memorial_id", type = IdType.AUTO)
    private Long memorialId;

    /**
     * 纪念设施业务编码。
     */
    @TableField("memorial_code")
    private String memorialCode;

    /**
     * 纪念设施名称。
     */
    @TableField("memorial_name")
    private String memorialName;

    /**
     * 关联的行政区划标识。
     */
    @TableField("region_id")
    private Long regionId;

    /**
     * 地址。
     */
    @TableField("address")
    private String address;

    /**
     * 经度，采用十进制度数表示。
     */
    @TableField("longitude")
    private BigDecimal longitude;

    /**
     * 纬度，采用十进制度数表示。
     */
    @TableField("latitude")
    private BigDecimal latitude;

    /**
     * 展陈内容。
     */
    @TableField("exhibition_content")
    private String exhibitionContent;

    /**
     * 简介。
     */
    @TableField("intro")
    private String intro;

    /**
     * 对外开放时间说明。
     */
    @TableField("opening_time_desc")
    private String openingTimeDesc;

    /**
     * 票务信息。
     */
    @TableField("ticket_info")
    private String ticketInfo;

    /**
     * 联系电话。
     */
    @TableField("contact_phone")
    private String contactPhone;

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
