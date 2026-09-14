package com.redculture.platform.vo;

import lombok.Data;
import java.time.LocalDateTime;

/** 学生最近资源视图对象。 */
@Data
public class StudentRecentResourceVO {
    /** 资源标识。 */
    private Long resourceId;
    /** 资源名称。 */
    private String resourceName;
    /** 资源主分类。 */
    private String resourceCategory;
    /** 详细地址。 */
    private String address;
    /** 浏览时间。 */
    private LocalDateTime viewedAt;
    /** 浏览数量。 */
    private Integer viewCount;
}
