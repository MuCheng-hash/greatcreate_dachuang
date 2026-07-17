package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class TownLocateResponse {

    private BigDecimal longitude;

    private BigDecimal latitude;

    private Boolean matched;

    private String matchMode;

    private String message;

    private TownBoundaryVO town;
}
