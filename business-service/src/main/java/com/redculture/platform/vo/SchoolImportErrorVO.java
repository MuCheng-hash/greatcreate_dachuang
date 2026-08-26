package com.redculture.platform.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SchoolImportErrorVO {
    private int rowNumber;
    private String schoolName;
    private String message;
}
