package com.redculture.platform.vo.request;

import com.redculture.platform.enums.SchoolResourceRelationType;
import lombok.Data;

import java.util.List;

@Data
public class SchoolResourceRelBatchCreateRequest {

    private List<Long> resourceIds;

    private Double radiusKm;

    private SchoolResourceRelationType relationType;
}
