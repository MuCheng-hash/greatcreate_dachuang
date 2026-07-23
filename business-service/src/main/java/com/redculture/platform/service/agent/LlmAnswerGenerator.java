package com.redculture.platform.service.agent;

import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.vo.AgentGenerationStatus;
import com.redculture.platform.vo.ai.AgentLlmRequest;
import com.redculture.platform.vo.ai.AgentLlmResponse;
import com.redculture.platform.vo.ai.AgentLlmScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;

@Component
@Primary
public class LlmAnswerGenerator implements AnswerGenerator {

    private final AppMapProperties appMapProperties;
    private final TemplateAnswerGenerator fallbackGenerator;

    @Autowired
    public LlmAnswerGenerator(AppMapProperties appMapProperties,
                              TemplateAnswerGenerator fallbackGenerator) {
        this.appMapProperties = appMapProperties;
        this.fallbackGenerator = fallbackGenerator;
    }

    public LlmAnswerGenerator(AppMapProperties appMapProperties) {
        this(appMapProperties, new TemplateAnswerGenerator());
    }

    @Override
    public GeneratedAnswer generate(AgentAnswerContext context) {
        if (context == null || !StringUtils.hasText(appMapProperties.getLlmServiceBaseUrl())) {
            return degradedFallback(context);
        }

        try {
            AgentLlmResponse response = RestClient.builder()
                    .baseUrl(appMapProperties.getLlmServiceBaseUrl())
                    .requestFactory(requestFactory())
                    .build()
                    .post()
                    .uri("/llm/agent/answer")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(toRequest(context))
                    .retrieve()
                    .body(AgentLlmResponse.class);
            if (response == null || !StringUtils.hasText(response.getAnswer())) {
                return degradedFallback(context);
            }
            return new GeneratedAnswer(
                    response.getAnswer(),
                    safeList(response.getCitationIds()),
                    safeList(response.getFollowUpQuestions()),
                    response.getGenerationStatus() == null
                            ? AgentGenerationStatus.COMPLETED
                            : response.getGenerationStatus()
            );
        } catch (RuntimeException exception) {
            return degradedFallback(context);
        }
    }

    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(25));
        return factory;
    }

    private AgentLlmRequest toRequest(AgentAnswerContext context) {
        AgentLlmRequest request = new AgentLlmRequest();
        request.setQuestion(context.getQuestion());
        request.setIntent(context.getIntent());
        request.setScope(new AgentLlmScope(
                context.getScopeType(),
                context.getScopeId(),
                schoolName(context)
        ));
        request.setGrade(context.getGrade());
        request.setTheme(context.getTheme());
        request.setBusinessContext(businessContext(context));
        request.setRetrievalResult(context.getRetrieval());
        return request;
    }

    private Map<String, Object> businessContext(AgentAnswerContext context) {
        Map<String, Object> businessContext = new LinkedHashMap<>();
        if (context.getSchoolDetail() != null) {
            businessContext.put("school", context.getSchoolDetail().getSchool());
            businessContext.put("resources", safeList(context.getSchoolDetail().getResources()));
            businessContext.put("activityPlans", safeList(context.getSchoolDetail().getActivityPlans()));
        }
        if (context.getRegionDetail() != null) {
            businessContext.put("region", context.getRegionDetail());
        }
        if (context.getResource() != null) {
            businessContext.put("resource", context.getResource());
        }
        if (context.getMatchedSchoolResource() != null) {
            businessContext.put("matchedResource", context.getMatchedSchoolResource());
        }
        return businessContext;
    }

    private String schoolName(AgentAnswerContext context) {
        if (context.getSchoolDetail() == null || context.getSchoolDetail().getSchool() == null) {
            return null;
        }
        return context.getSchoolDetail().getSchool().getSchoolName();
    }

    private GeneratedAnswer degradedFallback(AgentAnswerContext context) {
        GeneratedAnswer answer = context == null
                ? new GeneratedAnswer("暂时无法生成回答，请稍后重试。", List.of(), List.of())
                : fallbackGenerator.generate(context);
        answer.setGenerationStatus(AgentGenerationStatus.DEGRADED);
        return answer;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? new ArrayList<>() : values;
    }
}
