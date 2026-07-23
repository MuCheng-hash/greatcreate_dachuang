package com.redculture.platform.service.impl;

import com.redculture.platform.entity.LocalEduResource;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.service.AgentToolService;
import com.redculture.platform.service.KnowledgeRetriever;
import com.redculture.platform.service.LocalEduResourceService;
import com.redculture.platform.service.SchoolMapService;
import com.redculture.platform.service.agent.AgentAccessGuard;
import com.redculture.platform.vo.SchoolMapDetailVO;
import com.redculture.platform.vo.ai.AgentToolRequest;
import com.redculture.platform.vo.ai.KnowledgeRetrieveRequest;
import com.redculture.platform.vo.ai.KnowledgeRetrieveResult;
import com.redculture.platform.vo.ai.KnowledgeRetrievalStatus;
import com.redculture.platform.vo.ai.KnowledgeScopeType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AgentToolServiceImpl implements AgentToolService {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 8;

    private final AgentAccessGuard accessGuard;
    private final SchoolMapService schoolMapService;
    private final LocalEduResourceService localEduResourceService;
    private final KnowledgeRetriever knowledgeRetriever;

    public AgentToolServiceImpl(AgentAccessGuard accessGuard,
                                SchoolMapService schoolMapService,
                                LocalEduResourceService localEduResourceService,
                                KnowledgeRetriever knowledgeRetriever) {
        this.accessGuard = accessGuard;
        this.schoolMapService = schoolMapService;
        this.localEduResourceService = localEduResourceService;
        this.knowledgeRetriever = knowledgeRetriever;
    }

    @Override
    public Map<String, Object> schoolContext(AgentToolRequest request) {
        accessGuard.assertToolAccess(request);
        KnowledgeScopeType scopeType = scopeType(request);
        if (scopeType != KnowledgeScopeType.SCHOOL) {
            throw new IllegalArgumentException("school-context only supports SCHOOL scope");
        }
        SchoolMapDetailVO detail = schoolMapService.getSchoolDetail(request.getScope().getScopeId());
        if (detail == null) {
            throw new IllegalArgumentException("school not found or unavailable");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("school", detail.getSchool());
        result.put("resources", detail.getResources());
        result.put("activityPlans", detail.getActivityPlans());
        result.put("resourceCount", detail.getResourceCount());
        result.put("activityPlanCount", detail.getActivityPlanCount());
        result.put("grade", request.getGrade());
        result.put("theme", request.getTheme());
        return result;
    }

    @Override
    public LocalEduResource resourceDetail(AgentToolRequest request) {
        accessGuard.assertToolAccess(request);
        if (request.getResourceId() == null || request.getResourceId() <= 0) {
            throw new IllegalArgumentException("resourceId must be positive");
        }
        LocalEduResource resource = localEduResourceService.getById(request.getResourceId());
        if (resource == null || !Boolean.TRUE.equals(resource.getActive())
                || resource.getReviewStatus() != ReviewStatus.APPROVED) {
            throw new IllegalArgumentException("resource not found or unavailable");
        }
        if (!"platform_admin".equals(request.getActor().getRoleCode())) {
            SchoolMapDetailVO detail = schoolMapService.getSchoolDetail(request.getScope().getScopeId());
            boolean related = detail != null && detail.getResources() != null
                    && detail.getResources().stream().anyMatch(item ->
                    item != null && request.getResourceId().equals(item.getResourceId()));
            if (!related) {
                throw new IllegalArgumentException("resource is outside the current school scope");
            }
        }
        return resource;
    }

    @Override
    public KnowledgeRetrieveResult knowledgeRetrieve(AgentToolRequest request) {
        accessGuard.assertToolAccess(request);
        return retrieve(request, request.getQuery());
    }

    @Override
    public KnowledgeRetrieveResult relationQuery(AgentToolRequest request) {
        accessGuard.assertToolAccess(request);
        String query = StringUtils.hasText(request.getQuery()) ? request.getQuery().trim() : "关系查询";
        if (!query.contains("关系") && !query.contains("关联") && !query.contains("联系")) {
            query += " 关系";
        }
        return retrieve(request, query);
    }

    private KnowledgeRetrieveResult retrieve(AgentToolRequest request, String query) {
        KnowledgeRetrieveRequest retrieveRequest = new KnowledgeRetrieveRequest();
        retrieveRequest.setQuery(query);
        retrieveRequest.setScopeType(scopeType(request));
        retrieveRequest.setScopeId(request.getScope().getScopeId());
        retrieveRequest.setGrade(request.getGrade());
        retrieveRequest.setTheme(request.getTheme());
        retrieveRequest.setTopK(normalizeTopK(request.getTopK()));
        try {
            return normalize(knowledgeRetriever.retrieve(retrieveRequest));
        } catch (RuntimeException exception) {
            return KnowledgeRetrieveResult.degraded();
        }
    }

    private KnowledgeScopeType scopeType(AgentToolRequest request) {
        KnowledgeScopeType scopeType = KnowledgeScopeType.from(request.getScope().getScopeType());
        if (scopeType == null) {
            throw new IllegalArgumentException("scopeType is required");
        }
        return scopeType;
    }

    private KnowledgeRetrieveResult normalize(KnowledgeRetrieveResult result) {
        if (result == null) {
            return KnowledgeRetrieveResult.degraded();
        }
        if (result.getChunks() == null) {
            result.setChunks(new ArrayList<>());
        }
        if (result.getGraphFacts() == null) {
            result.setGraphFacts(new ArrayList<>());
        }
        if (result.getCitationCandidates() == null) {
            result.setCitationCandidates(new ArrayList<>());
        }
        if (result.getRetrievalStatus() == null) {
            result.setRetrievalStatus(result.getChunks().isEmpty()
                    && result.getGraphFacts().isEmpty()
                    && result.getCitationCandidates().isEmpty()
                    ? KnowledgeRetrievalStatus.EMPTY
                    : KnowledgeRetrievalStatus.OK);
        }
        return result;
    }

    private int normalizeTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, MAX_TOP_K);
    }
}
