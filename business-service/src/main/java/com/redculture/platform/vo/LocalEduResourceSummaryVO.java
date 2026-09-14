package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;

/** 本地教育资源摘要视图对象。 */
@Data
public class LocalEduResourceSummaryVO {

    /** 资源标识。 */
    private Long resourceId;

    /** 资源编码。 */
    private String resourceCode;

    /** 资源名称。 */
    private String resourceName;

    /** 资源主分类。 */
    private String resourceCategory;

    /** 资源子分类。 */
    private String resourceSubcategory;

    /** 详细地址。 */
    private String address;

    /** 经度坐标。 */
    private BigDecimal longitude;

    /** 纬度坐标。 */
    private BigDecimal latitude;

    /** 简介。 */
    private String intro;

    /** 教育值。 */
    private String educationValue;

    /** 目标年级。 */
    private String targetGrade;
}
