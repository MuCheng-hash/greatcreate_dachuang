package com.redculture.platform.vo.ai;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Agent工具请求参数。 */
@Data
public class AgentToolRequest {

    /** 执行主体。 */
    private AgentActorVO actor;

    /** 范围。 */
    private AgentScopeVO scope;

    /** 查询。 */
    private String query;

    /** 年级。 */
    private String grade;

    /** 主题。 */
    private String theme;

    /** 资源主分类。 */
    private String resourceCategory;

    /** 允许查询的最大距离，单位为米。 */
    private Integer maxDistanceMeters;

    /** 最多返回的候选结果数量。 */
    private Integer topK;

    /** 资源标识。 */
    private Long resourceId;

    /** 学生任务标识，仅可由已验证的工具授权凭据注入。 */
    private Long taskId;

    /** 意图。 */
    private String intent;

    /** 用于补充稠密检索的假设性答案查询文本。 */
    private String hydeQuery;

    /** 由可信服务端筛选后提供的网络证据列表。 */
    private List<WebEvidenceVO> webEvidence = new ArrayList<>();
}
