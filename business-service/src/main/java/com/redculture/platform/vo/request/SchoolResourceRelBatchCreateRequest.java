package com.redculture.platform.vo.request;

import com.redculture.platform.enums.SchoolResourceRelationType;
import lombok.Data;

import java.util.List;

/** 学校资源关联批量创建请求参数。 */
@Data
public class SchoolResourceRelBatchCreateRequest {

    /** 资源标识列表。 */
    private List<Long> resourceIds;

    /** 查询半径，单位为公里。 */
    private Double radiusKm;

    /**
     * 关联类型。
     * 具体取值由资源、学校或知识图谱等对应关系模型定义。
     */
    private SchoolResourceRelationType relationType;
}
