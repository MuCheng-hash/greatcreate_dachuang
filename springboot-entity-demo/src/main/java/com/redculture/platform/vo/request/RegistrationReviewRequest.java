package com.redculture.platform.vo.request;

import lombok.Data;

@Data
public class RegistrationReviewRequest {

    private Long linkedSchoolId;

    private String reviewerName;

    private String reviewRemark;
}
