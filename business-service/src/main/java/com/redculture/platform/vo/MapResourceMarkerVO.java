package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class MapResourceMarkerVO {

    private Long id;

    private String type;

    private String name;

    private BigDecimal longitude;

    private BigDecimal latitude;

    private String address;

    private String summary;

    private String relationHint;
}
