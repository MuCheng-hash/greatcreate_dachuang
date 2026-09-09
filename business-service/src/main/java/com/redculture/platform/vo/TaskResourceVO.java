package com.redculture.platform.vo;
import lombok.Data;
import java.math.BigDecimal;
/** 任务资源视图对象。 */
@Data public class TaskResourceVO { /** 资源标识。 */ private Long resourceId; /** 资源名称。 */ private String resourceName; /** 详细地址。 */ private String address; /** 经度坐标。 */ private BigDecimal longitude; /** 纬度坐标。 */ private BigDecimal latitude; /** 简介。 */ private String intro; /** 教育值。 */ private String educationValue; /** 安全说明。 */ private String safetyNote; }
