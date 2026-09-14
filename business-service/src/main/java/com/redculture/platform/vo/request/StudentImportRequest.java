package com.redculture.platform.vo.request;

import lombok.Data;

import java.util.List;

/** 学生导入请求参数。 */
@Data
public class StudentImportRequest {
    /** 行列表。 */
    private List<StudentImportRowRequest> rows;
}
