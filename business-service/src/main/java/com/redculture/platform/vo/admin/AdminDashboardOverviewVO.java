package com.redculture.platform.vo.admin;

import lombok.Data;

import java.util.Map;

/** 管理端工作台概览视图对象。 */
@Data
public class AdminDashboardOverviewVO {
    /** 资源数量。 */
    private long resourceCount;
    /** 学校数量。 */
    private long schoolCount;
    /** 教师数量。 */
    private long teacherCount;
    /** 学生数量。 */
    private long studentCount;
    /** 教学方案数量。 */
    private long teachingPlanCount;
    /** 问题数量。 */
    private Long questionCount;
    /**
     * 问题状态。
     * 具体取值由对应业务流程或协议定义。
     */
    private String questionStatus;
    /** RAG 服务状态信息。 */
    private Map<String, Object> ragStatus;
    /** 待处理投影数量。 */
    private long pendingProjectionCount;
    /** 检索投影状态信息。 */
    private Map<String, Object> projectionStatus;
}
