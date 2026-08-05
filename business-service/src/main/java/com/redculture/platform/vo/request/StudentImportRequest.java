package com.redculture.platform.vo.request;

import lombok.Data;

import java.util.List;

@Data
public class StudentImportRequest {
    private List<StudentImportRowRequest> rows;
}
