package com.redculture.platform.vo.request;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 班级任务保存请求参数。 */
@Data
public class ClassTaskSaveRequest {
    /** 标题。 */
    private String title;
    /** 描述信息。 */
    private String description;
    /** 截止时间。 */
    private LocalDateTime dueAt;
    /** 开始时间。 */
    private LocalDateTime startAt;
    /**
     * 任务类型。
     * 具体取值由对应业务流程或协议定义。
     */
    private String taskType = "red_culture_learning";
    /** 任务提交规则说明。 */
    private String submissionRule = "text_required";
    /** 是否允许超过截止时间后提交。 */
    private Boolean allowLateSubmission = true;
    /** 资源标识列表。 */
    private List<Long> resourceIds = new ArrayList<>();
}
