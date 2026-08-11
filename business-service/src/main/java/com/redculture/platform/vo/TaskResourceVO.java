package com.redculture.platform.vo;
import lombok.Data;
import java.math.BigDecimal;
@Data public class TaskResourceVO { private Long resourceId; private String resourceName; private String address; private BigDecimal longitude; private BigDecimal latitude; private String intro; private String educationValue; private String safetyNote; }
