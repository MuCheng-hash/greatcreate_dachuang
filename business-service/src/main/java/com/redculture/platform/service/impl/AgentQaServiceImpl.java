package com.redculture.platform.service.impl;

import com.redculture.platform.entity.LocalEduResource;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.service.AgentQaService;
import com.redculture.platform.service.KnowledgeRetriever;
import com.redculture.platform.service.LocalEduResourceService;
import com.redculture.platform.service.SchoolMapService;
import com.redculture.platform.service.TownMapService;
import com.redculture.platform.service.agent.AgentAnswerContext;
import com.redculture.platform.service.agent.AnswerGenerator;
import com.redculture.platform.service.agent.CitationValidator;
import com.redculture.platform.service.agent.GeneratedAnswer;
import com.redculture.platform.service.agent.IntentRecognizer;
import com.redculture.platform.vo.AgentIntent;
import com.redculture.platform.vo.AgentQaResponse;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.LocalEduResourceSummaryVO;
import com.redculture.platform.vo.SchoolMapDetailVO;
import com.redculture.platform.vo.SchoolResourceItemVO;
import com.redculture.platform.vo.ai.KnowledgeRetrieveRequest;
import com.redculture.platform.vo.ai.KnowledgeRetrieveResult;
import com.redculture.platform.vo.ai.KnowledgeRetrievalStatus;
import com.redculture.platform.vo.ai.KnowledgeScopeType;
import com.redculture.platform.vo.request.AgentQaRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class AgentQaServiceImpl implements AgentQaService {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 8;

    private final SchoolMapService schoolMapService;
    private final TownMapService townMapService;
    private final LocalEduResourceService localEduResourceService;
    private final KnowledgeRetriever knowledgeRetriever;
    private final IntentRecognizer intentRecognizer;
    private final AnswerGenerator answerGenerator;
    private final CitationValidator citationValidator;

    public AgentQaServiceImpl(SchoolMapService schoolMapService,
                              TownMapService townMapService,
                              LocalEduResourceService localEduResourceService,
                              KnowledgeRetriever knowledgeRetriever,
                              IntentRecognizer intentRecognizer,
                              AnswerGenerator answerGenerator,
                              CitationValidator citationValidator) {
        this.schoolMapService = schoolMapService;
        this.townMapService = townMapService;
        this.localEduResourceService = localEduResourceService;
        this.knowledgeRetriever = knowledgeRetriever;
        this.intentRecognizer = intentRecognizer;
        this.answerGenerator = answerGenerator;
        this.citationValidator = citationValidator;
    }

    @Override
    public AgentQaResponse ask(AgentQaRequest request, AuthCurrentUserVO currentUser) {
        validateRequest(request);
        if (currentUser == null) {
            throw new IllegalArgumentException("school account is required");
        }

        Scope scope = resolveScope(request, currentUser);
        String question = request.getQuestion().trim();
        AgentIntent intent = intentRecognizer.recognize(question);

        AgentAnswerContext context = new AgentAnswerContext();
        context.setQuestion(question);
        context.setIntent(intent);
        context.setScopeType(scope.type());
        context.setScopeId(scope.id());
        context.setGrade(clean(request.getGrade()));
        context.setTheme(clean(request.getTheme()));
        loadBusinessContext(context);

        KnowledgeRetrieveResult retrieval = retrieve(context, request.getTopK());
        context.setRetrieval(retrieval);

        GeneratedAnswer generated = answerGenerator.generate(context);
        if (generated == null) {
            generated = new GeneratedAnswer("暂时无法生成回答，请稍后重试。", List.of(), List.of());
        }

        AgentQaResponse response = new AgentQaResponse();
        response.setAnswer(StringUtils.hasText(generated.getAnswer()) ? generated.getAnswer() : "暂时无法生成回答。");
        response.setIntent(intent);
        response.setRetrievalStatus(retrieval.getRetrievalStatus());
        response.setScopeType(scope.type());
        response.setScopeId(scope.id());
        response.setRelatedResources(relatedResources(context));
        response.setCitations(citationValidator.filter(generated.getCitationIds(), retrieval));
        response.setFollowUpQuestions(nonNullList(generated.getFollowUpQuestions()));
        return response;
    }

    private void validateRequest(AgentQaRequest request) {
        if (request == null || !StringUtils.hasText(request.getQuestion())) {
            throw new IllegalArgumentException("question is required");
        }
        if (request.getScopeId() != null && request.getScopeId() <= 0) {
            throw new IllegalArgumentException("scopeId must be positive");
        }
    }

    private Scope resolveScope(AgentQaRequest request, AuthCurrentUserVO currentUser) {
        KnowledgeScopeType requestedType = KnowledgeScopeType.from(request.getScopeType());
        Long requestedId = request.getScopeId();
        boolean admin = "platform_admin".equals(currentUser.getRoleCode());

        if (!admin) {
            if (currentUser.getSchoolId() == null) {
                throw new IllegalArgumentException("school account is required");
            }
            if (requestedType != null && requestedType != KnowledgeScopeType.SCHOOL) {
                throw new IllegalArgumentException("school account can only query its own school");
            }
            if (requestedId != null && !requestedId.equals(currentUser.getSchoolId())) {
                throw new IllegalArgumentException("cannot access another school");
            }
            return new Scope(KnowledgeScopeType.SCHOOL, currentUser.getSchoolId());
        }

        if (requestedType == null && currentUser.getSchoolId() != null) {
            requestedType = KnowledgeScopeType.SCHOOL;
            requestedId = currentUser.getSchoolId();
        }
        if (requestedType == null || requestedId == null) {
            throw new IllegalArgumentException("scopeType and scopeId are required for an administrator");
        }
        return new Scope(requestedType, requestedId);
    }

    private void loadBusinessContext(AgentAnswerContext context) {
        switch (context.getScopeType()) {
            case SCHOOL -> {
                SchoolMapDetailVO detail = schoolMapService.getSchoolDetail(context.getScopeId());
                if (detail == null) {
                    throw new IllegalArgumentException("school not found or unavailable");
                }
                context.setSchoolDetail(detail);
                context.setMatchedSchoolResource(findMentionedResource(detail, context.getQuestion()));
            }
            case REGION -> {
                if (townMapService != null) {
                    context.setRegionDetail(townMapService.getTownMapDetail(context.getScopeId()));
                }
                if (context.getRegionDetail() == null) {
                    throw new IllegalArgumentException("region not found or unavailable");
                }
            }
            case RESOURCE -> {
                LocalEduResource resource = localEduResourceService.getById(context.getScopeId());
                if (resource == null || !Boolean.TRUE.equals(resource.getActive())
                        || resource.getReviewStatus() != ReviewStatus.APPROVED) {
                    throw new IllegalArgumentException("resource not found or unavailable");
                }
                context.setResource(resource);
            }
        }
    }

    private KnowledgeRetrieveResult retrieve(AgentAnswerContext context, Integer requestedTopK) {
        if (context.getIntent() == AgentIntent.UNKNOWN) {
            return KnowledgeRetrieveResult.empty();
        }

        KnowledgeRetrieveRequest request = new KnowledgeRetrieveRequest();
        request.setQuery(context.getQuestion());
        request.setScopeType(context.getScopeType());
        request.setScopeId(context.getScopeId());
        request.setGrade(context.getGrade());
        request.setTheme(context.getTheme());
        request.setTopK(normalizeTopK(requestedTopK));

        try {
            return normalizeResult(knowledgeRetriever.retrieve(request));
        } catch (RuntimeException exception) {
            return KnowledgeRetrieveResult.degraded();
        }
    }

    private KnowledgeRetrieveResult normalizeResult(KnowledgeRetrieveResult result) {
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
            boolean hasEvidence = !result.getChunks().isEmpty()
                    || !result.getGraphFacts().isEmpty()
                    || !result.getCitationCandidates().isEmpty();
            result.setRetrievalStatus(hasEvidence ? KnowledgeRetrievalStatus.OK : KnowledgeRetrievalStatus.EMPTY);
        }
        return result;
    }

    private List<String> relatedResources(AgentAnswerContext context) {
        List<String> names = new ArrayList<>();
        if (context.getResource() != null && StringUtils.hasText(context.getResource().getResourceName())) {
            names.add(context.getResource().getResourceName());
        }
        SchoolMapDetailVO detail = context.getSchoolDetail();
        if (detail != null && detail.getResources() != null) {
            detail.getResources().stream()
                    .map(SchoolResourceItemVO::getResource)
                    .filter(resource -> resource != null && StringUtils.hasText(resource.getResourceName()))
                    .map(LocalEduResourceSummaryVO::getResourceName)
                    .filter(name -> !names.contains(name))
                    .limit(8)
                    .forEach(names::add);
        }
        if (context.getRegionDetail() != null && context.getRegionDetail().getMarkers() != null) {
            context.getRegionDetail().getMarkers().stream()
                    .filter(marker -> marker != null && StringUtils.hasText(marker.getName()))
                    .map(marker -> marker.getName())
                    .filter(name -> !names.contains(name))
                    .limit(8)
                    .forEach(names::add);
        }
        return names;
    }

    private LocalEduResourceSummaryVO findMentionedResource(SchoolMapDetailVO detail, String question) {
        if (detail == null || detail.getResources() == null || question == null) {
            return null;
        }
        return detail.getResources().stream()
                .map(SchoolResourceItemVO::getResource)
                .filter(resource -> resource != null && StringUtils.hasText(resource.getResourceName()))
                .filter(resource -> question.contains(resource.getResourceName()))
                .findFirst()
                .orElse(null);
    }

    private Integer normalizeTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, MAX_TOP_K);
    }

    private List<String> nonNullList(List<String> values) {
        return values == null ? new ArrayList<>() : values;
    }

    private String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private record Scope(KnowledgeScopeType type, Long id) {
    }
}
