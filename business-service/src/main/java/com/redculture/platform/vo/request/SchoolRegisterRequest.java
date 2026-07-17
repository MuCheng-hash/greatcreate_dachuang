package com.redculture.platform.vo.request;

import com.redculture.platform.enums.GeoConfidenceLevel;
import com.redculture.platform.enums.GeoSourceType;
import com.redculture.platform.enums.SchoolLevel;
import com.redculture.platform.enums.SchoolNature;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class SchoolRegisterRequest {

    private String username;

    private String password;

    private String contactName;

    private String contactPhone;

    private String contactEmail;

    private String schoolName;

    private String schoolAlias;

    private SchoolLevel schoolLevel;

    private String schoolType;

    private SchoolNature schoolNature;

    private Long countyRegionId;

    private Long townshipRegionId;

    private String address;

    private BigDecimal longitude;

    private BigDecimal latitude;

    private GeoSourceType geoSourceType;

    private GeoConfidenceLevel geoConfidence;

    private String intro;
}
