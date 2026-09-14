package com.redculture.platform.vo.ai;

import com.redculture.platform.vo.GeneratedTeachingPlanCitationVO;
import com.redculture.platform.vo.LocalEduResourceSummaryVO;
import com.redculture.platform.vo.SchoolSummaryVO;
import com.redculture.platform.vo.TeachingActivityPlanVO;
import com.redculture.platform.vo.request.TeachingPlanGenerateRequest;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 教学方案生成阶段使用的可信上下文。 */
@Data
public class TeachingPlanContextVO {

    /** 请求。 */
    private TeachingPlanGenerateRequest request;

    /** 执行主体。 */
    private AgentActorVO actor;

    /** 会话标识。 */
    private String sessionId;

    /** 学校。 */
    private SchoolSummaryVO school;

    /** 资源列表。 */
    private List<ResourceContextVO> resources = new ArrayList<>();

    /** 已选择资源列表。 */
    private List<ResourceContextVO> selectedResources = new ArrayList<>();

    /** 已有方案列表。 */
    private List<TeachingActivityPlanVO> existingPlans = new ArrayList<>();

    /** 内容分块列表。 */
    private List<ContentChunkContextVO> contentChunks = new ArrayList<>();

    /** 可供生成阶段选择和展示的引用候选列表。 */
    private List<GeneratedTeachingPlanCitationVO> citationCandidates = new ArrayList<>();

    /** 知识图谱事实列表。 */
    private List<String> graphFacts = new ArrayList<>();

    /**
     * 知识检索状态。
     * 常见取值包括 {@code ok}、{@code empty} 和 {@code degraded}。
     */
    private String retrievalStatus;

    /** 教学方案生成使用的资源上下文。 */
    @Data
    public static class ResourceContextVO {

        /** 资源标识。 */
        private Long resourceId;

        /**
         * 关联类型。
         * 具体取值由资源、学校或知识图谱等对应关系模型定义。
         */
        private String relationType;

        /** 距离，单位为米。 */
        private Integer distanceMeters;

        /** 出行方式。 */
        private String travelMode;

        /** 资源可达性等级。 */
        private String reachabilityLevel;

        /** 教育主题摘要。 */
        private String educationThemeSummary;

        /** 资源。 */
        private LocalEduResourceSummaryVO resource;
    }

    /** 教学方案生成使用的内容分块上下文。 */
    @Data
    public static class ContentChunkContextVO {

        /** 分块标识。 */
        private Long chunkId;

        /**
         * 实体类型。
         * 用于确定 {@code entityId} 所指向的具体业务实体。
         */
        private String entityType;

        /**
         * 实体标识。
         * 其实际对象类型由 {@code entityType} 决定。
         */
        private Long entityId;

        /** 标题。 */
        private String title;

        /** 文本内容。 */
        private String text;

        /** 来源记录标识，用于追溯当前数据的原始依据。 */
        private Long sourceId;

        /** 当前候选结果的相关性得分。 */
        private Double score;

        /** 产生当前结果时使用的检索方式。 */
        private String retrievalMethod;
    }
}
