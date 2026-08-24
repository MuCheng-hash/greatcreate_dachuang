package com.redculture.platform.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class StudentTaskPageVO {
    private List<ClassTaskVO> records = new ArrayList<>();
    private long total;
    private long pageNum;
    private long pageSize;
    private StudentTaskSummaryVO summary = new StudentTaskSummaryVO();
}
