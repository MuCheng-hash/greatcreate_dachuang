package com.redculture.platform.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 学生任务分页视图对象。 */
@Data
public class StudentTaskPageVO {
    /** 记录列表。 */
    private List<ClassTaskVO> records = new ArrayList<>();
    /** 总计。 */
    private long total;
    /** 分页编号。 */
    private long pageNum;
    /** 分页大小。 */
    private long pageSize;
    /** 摘要信息。 */
    private StudentTaskSummaryVO summary = new StudentTaskSummaryVO();
}
