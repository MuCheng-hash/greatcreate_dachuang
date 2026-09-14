package com.redculture.platform.vo.request;
import lombok.Data;
/** 教师资源查询请求参数。 */
@Data public class TeacherResourceQueryRequest { /** 关键词。 */ private String keyword; /** 分类。 */ private String category; /** 年级名称。 */ private String gradeName; /** 出行方式。 */ private String travelMode; /** 资源可达性等级。 */ private String reachabilityLevel; /** 允许查询的最大距离，单位为米。 */ private Integer maxDistanceMeters; /** 是否只查询已收藏资源。 */ private Boolean favoritesOnly; }
