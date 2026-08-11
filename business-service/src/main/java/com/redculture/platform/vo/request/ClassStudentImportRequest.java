package com.redculture.platform.vo.request;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class ClassStudentImportRequest {
    private List<String> studentNos = new ArrayList<>();
}
