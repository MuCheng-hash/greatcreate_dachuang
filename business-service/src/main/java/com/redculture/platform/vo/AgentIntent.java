package com.redculture.platform.vo;

/** Agent 识别出的用户问题意图枚举。 */
public enum AgentIntent {

    /** 查询学校或当前位置周边的教育资源。 */
    NEARBY_RESOURCE,
    /** 请求生成教学建议。 */
    TEACHING_SUGGESTION,
    /** 请求解释某个教育资源。 */
    RESOURCE_EXPLANATION,
    /** 查询学校、资源或区域之间的关系。 */
    RELATION_QUERY,
    /** 无法归入已知类型的意图。 */
    UNKNOWN
}
