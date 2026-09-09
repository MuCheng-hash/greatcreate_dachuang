package com.redculture.platform.vo.request;

import com.redculture.platform.enums.ActivityType;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 教学活动方案创建请求参数。 */
@Data
public class TeachingActivityPlanCreateRequest {

    /** 方案编码。 */
    private String planCode;

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

    /** 适用年级。 */
    private String suitableGrade;

    /** 目标文本。 */
    private String objectiveText;

    /** 活动内容。 */
    private String activityContent;

    /** 准备事项文本。 */
    private String preparationText;

    /** 安全文本。 */
    private String safetyText;

    /** 预期结果。 */
    private String expectedOutcome;

    /** 时长，单位为分钟。 */
    private Integer durationMinutes;

    /** 来源记录标识，用于追溯当前数据的原始依据。 */
    private Long sourceId;
}
