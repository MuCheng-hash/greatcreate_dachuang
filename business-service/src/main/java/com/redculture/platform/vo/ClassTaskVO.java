package com.redculture.platform.vo;

import lombok.Data;
import java.time.LocalDateTime;

/** 班级任务视图对象。 */
@Data
public class ClassTaskVO {
    /** 任务标识。 */
    private Long taskId;
    /** 班级标识。 */
    private Long classId;
    /** 标题。 */
    private String title;
    /** 描述信息。 */
    private String description;
    /** 发布者名称。 */
    private String publisherName;
    /** 发布时间。 */
    private LocalDateTime publishedAt;
    /** 截止时间。 */
    private LocalDateTime dueAt;
    /** 开始时间。 */
    private LocalDateTime startAt;
    /** 材料文件名。 */
    private String materialFilename;
    /**
     * 当前对象的业务状态。
     * 具体取值由所属业务流程定义；为空表示尚未提供状态。
     */
    private String status;
    /**
     * 任务类型。
     * 具体取值由对应业务流程或协议定义。
     */
    private String taskType;
    /** 任务提交规则说明。 */
    private String submissionRule;
    /** 是否允许超过截止时间后提交。 */
    private boolean allowLateSubmission;
    /** 总计数量。 */
    private long totalCount;
    /** 已完成数量。 */
    private long completedCount;
    /** 逾期数量。 */
    private long overdueCount;
    /**
     * 学生状态。
     * 具体取值由对应业务流程或协议定义。
     */
    private String studentStatus;
    /** 资源数量。 */
    private long resourceCount;
    /** 完成时间。 */
    private java.time.LocalDateTime completedAt;
    /** 提交时间。 */
    private java.time.LocalDateTime submittedAt;
}
