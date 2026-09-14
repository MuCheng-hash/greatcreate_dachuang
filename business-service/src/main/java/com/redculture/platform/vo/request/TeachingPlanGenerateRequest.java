package com.redculture.platform.vo.request;

import com.redculture.platform.enums.ActivityType;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 教学方案生成请求参数。 */
@Data
public class TeachingPlanGenerateRequest {

    /**
     * Agent 侧的多轮对话线程标识。
     * 同一段连续对话复用该值，以便加载历史消息并延续上下文。
     */
    private String threadId;

    /** 请求指定的模型标识；为空时由服务端选择默认模型。 */
    private String modelId;

    /** 学校标识。 */
    private Long schoolId;

    /** 年级。 */
    private String grade;

    /** 主题。 */
    private String theme;

    /** 目标。 */
    private String objectives;

    /** 资源标识列表。 */
    private List<Long> resourceIds = new ArrayList<>();

    /** 为兼容旧客户端保留的单资源标识。 */
    private Long resourceId;

    /** 教学活动类型。 */
    private ActivityType activityType;

    /** 时长，单位为分钟。 */
    private Integer durationMinutes;

    /** 教学方案是否要求包含实践活动。 */
    private Boolean practiceRequired;
}
