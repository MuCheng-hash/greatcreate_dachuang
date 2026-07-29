package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class RedCultureSiteMarkerVO {
    private String id;
    private String name;
    private String category;
    private String address;
    private String district;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private String summary;
}
