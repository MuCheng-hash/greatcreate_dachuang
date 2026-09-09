package com.redculture.platform.vo;
import lombok.Data;
/** 任务统计视图对象。 */
@Data public class TaskStatisticsVO { /** 任务标识。 */ private Long taskId; /** 待处理数量。 */ private long pendingCount; /** 提交数量。 */ private long submittedCount; /** 已退回数量。 */ private long returnedCount; /** 已完成数量。 */ private long completedCount; /** 逾期数量。 */ private long overdueCount; /** 逾期提交数量。 */ private long lateSubmissionCount; }
