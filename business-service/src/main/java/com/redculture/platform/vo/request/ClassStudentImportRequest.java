package com.redculture.platform.vo.request;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/** 班级学生导入请求参数。 */
@Data
public class ClassStudentImportRequest {
    /** 学生编号列表。 */
    private List<String> studentNos = new ArrayList<>();
}
