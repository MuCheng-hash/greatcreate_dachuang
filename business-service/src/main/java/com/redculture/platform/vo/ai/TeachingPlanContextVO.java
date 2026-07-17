package com.redculture.platform.vo.ai;

import com.redculture.platform.vo.GeneratedTeachingPlanCitationVO;
import com.redculture.platform.vo.LocalEduResourceSummaryVO;
import com.redculture.platform.vo.SchoolSummaryVO;
import com.redculture.platform.vo.TeachingActivityPlanVO;
import com.redculture.platform.vo.request.TeachingPlanGenerateRequest;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TeachingPlanContextVO {

    private TeachingPlanGenerateRequest request;

    private SchoolSummaryVO school;

    private List<ResourceContextVO> resources = new ArrayList<>();

    private List<TeachingActivityPlanVO> existingPlans = new ArrayList<>();

    private List<ContentChunkContextVO> contentChunks = new ArrayList<>();

    private List<GeneratedTeachingPlanCitationVO> citationCandidates = new ArrayList<>();

    private List<String> graphFacts = new ArrayList<>();

    @Data
    public static class ResourceContextVO {

        private Long resourceId;

        private String relationType;

        private Integer distanceMeters;

        private String travelMode;

        private String reachabilityLevel;

        private String educationThemeSummary;

        private LocalEduResourceSummaryVO resource;
    }

    @Data
    public static class ContentChunkContextVO {

        private Long chunkId;

        private String entityType;

        private Long entityId;

        private String title;

        private String text;

        private Long sourceId;
    }
}
