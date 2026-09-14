package com.redculture.platform.vo;

import lombok.Data;

/** 公开学校视图对象。 */
@Data
public class PublicSchoolVO {
    /** 学校标识。 */
    private Long schoolId;
    /** 学校名称。 */
    private String schoolName;
    /** 行政区域名称。 */
    private String regionName;
    /**
     * 学校类型。
     * 具体取值由对应业务流程或协议定义。
     */
    private String schoolType;
}
