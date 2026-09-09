package com.redculture.platform.vo;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;
/** 学生任务详情视图对象。 */
@Data public class StudentTaskDetailVO extends ClassTaskVO { /** 任务类型。 具体取值由对应业务流程或协议定义。 */ private String taskType; /** 任务提交规则说明。 */ private String submissionRule; /** 是否允许超过截止时间后提交。 */ private boolean allowLateSubmission; /** 资源列表。 */ private List<TaskResourceVO> resources = new ArrayList<>(); /** 当前提交。 */ private StudentTaskSubmissionVO currentSubmission; }
