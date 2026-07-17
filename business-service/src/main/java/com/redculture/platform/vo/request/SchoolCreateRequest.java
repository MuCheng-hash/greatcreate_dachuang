package com.redculture.platform.vo.request;

import com.redculture.platform.enums.GeoConfidenceLevel;
import com.redculture.platform.enums.GeoSourceType;
import com.redculture.platform.enums.SchoolLevel;
import com.redculture.platform.enums.SchoolNature;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class SchoolCreateRequest {

    private String schoolCode;

    private String schoolName;

    private String schoolAlias;

    private Long regionId;

    private Long countyRegionId;

    private Long townshipRegionId;

    private Long villageRegionId;

    private SchoolLevel schoolLevel;

    private String schoolType;

    private SchoolNature schoolNature;

    private Boolean ruralSchool;

    private Boolean teachingPoint;

    private String address;

    private String postcode;

    private String contactPhone;

    private String principalName;

    private BigDecimal longitude;

    private BigDecimal latitude;

    private GeoSourceType geoSourceType;

    private String poiName;

    private String poiAddress;

    private String poiType;

    private GeoConfidenceLevel geoConfidence;

    private Boolean geoVerified;

    private String intro;

    private Long sourceId;
}
