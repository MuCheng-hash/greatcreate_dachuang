package com.redculture.platform.vo.request;

import com.redculture.platform.enums.ReachabilityLevel;
import com.redculture.platform.enums.SchoolResourceRelationType;
import com.redculture.platform.enums.TravelMode;
import lombok.Data;

/** 学校资源关联创建请求参数。 */
@Data
public class SchoolResourceRelCreateRequest {

    /** 学校标识。 */
    private Long schoolId;

    /** 资源标识。 */
    private Long resourceId;

    /**
     * 关联类型。
     * 具体取值由资源、学校或知识图谱等对应关系模型定义。
     */
    private SchoolResourceRelationType relationType;

    /** 距离，单位为米。 */
    private Integer distanceMeters;

    /** 建议采用的出行方式。 */
    private TravelMode recommendedTravelMode;

    /** 预计时长，单位为分钟。 */
    private Integer estimatedDurationMinutes;

    /** 资源可达性等级。 */
    private ReachabilityLevel reachabilityLevel;

    /** 资源或关联项的业务优先级。 */
    private Integer priorityLevel;

    /** 教育主题摘要。 */
    private String educationThemeSummary;

    /** 来源记录标识，用于追溯当前数据的原始依据。 */
    private Long sourceId;
}
