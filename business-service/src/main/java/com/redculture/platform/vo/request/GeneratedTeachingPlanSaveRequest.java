package com.redculture.platform.vo.request;

import com.redculture.platform.enums.ActivityType;
import lombok.Data;

import java.util.List;
import java.util.ArrayList;

/** 已生成教学方案保存请求参数。 */
@Data
public class GeneratedTeachingPlanSaveRequest {

    /** 生成标识。 */
    private Long generationId;

    /** 学校标识。 */
    private Long schoolId;

    /** 资源标识。 */
    private Long resourceId;

    /** 资源标识列表。 */
    private List<Long> resourceIds = new ArrayList<>();

    /** 所属用户账号标识。 */
    private Long ownerAccountId;

    /**
     * 教学方案的序列化载荷。
     * 具体结构由教学方案保存协议定义。
     */
    private String planPayload;

    /** 教学方案或内容的生成来源。 */
    private String generationSource;

    /** AI运行标识。 */
    private Long aiRunId;

    /** 主题。 */
    private String theme;

    /** 教学活动类型。 */
    private ActivityType activityType;

    /** 年级。 */
    private String grade;

    /** 时长，单位为分钟。 */
    private Integer durationMinutes;

    /** 目标列表。 */
    private List<String> objectives;

    /** 活动流程列表。 */
    private List<String> activityFlow;

    /** 准备事项列表。 */
    private List<String> preparation;

    /** 安全说明列表。 */
    private List<String> safetyNotes;

    /** 反思列表。 */
    private List<String> reflection;

    /** 评价列表。 */
    private List<String> evaluation;

    /** 资源依据列表。 */
    private List<String> resourceBasis = new ArrayList<>();

    /** 实地任务列表。 */
    private List<String> fieldTasks = new ArrayList<>();

    /** 与当前结果关联的资源名称列表。 */
    private List<String> relatedResources = new ArrayList<>();

    /** 用于支撑当前结果的引用证据列表。 */
    private List<Object> citations = new ArrayList<>();
}
