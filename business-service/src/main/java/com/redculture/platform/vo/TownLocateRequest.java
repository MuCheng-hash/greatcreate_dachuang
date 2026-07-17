package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class TownLocateRequest {

    private BigDecimal longitude;

    private BigDecimal latitude;
}
