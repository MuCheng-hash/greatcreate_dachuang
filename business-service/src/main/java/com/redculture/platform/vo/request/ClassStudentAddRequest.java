package com.redculture.platform.vo.request;

import lombok.Data;

/** 班级学生新增请求参数。 */
@Data
public class ClassStudentAddRequest {
    /** 学生标识。 */
    private Long studentId;
}
