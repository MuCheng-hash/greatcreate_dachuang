package com.redculture.platform.vo;
import lombok.Data;
import java.math.BigDecimal;
/** 教师资源视图对象。 */
@Data public class TeacherResourceVO { /** 资源标识。 */ private Long resourceId; /** 资源名称。 */ private String resourceName; /** 资源主分类。 */ private String resourceCategory; /** 目标年级。 */ private String targetGrade; /** 详细地址。 */ private String address; /** 经度坐标。 */ private BigDecimal longitude; /** 纬度坐标。 */ private BigDecimal latitude; /** 简介。 */ private String intro; /** 教育值。 */ private String educationValue; /** 安全说明。 */ private String safetyNote; /** 距离，单位为米。 */ private Integer distanceMeters; /** 资源或关联项的业务优先级。 */ private Integer priorityLevel; /** 出行方式。 */ private String travelMode; /** 时长，单位为分钟。 */ private Integer durationMinutes; /** 资源可达性等级。 */ private String reachabilityLevel; /** 教育主题摘要。 */ private String educationThemeSummary; /** 当前用户是否已收藏该资源。 */ private boolean favorited; /** 当前对象是否可用。 */ private boolean available; }
