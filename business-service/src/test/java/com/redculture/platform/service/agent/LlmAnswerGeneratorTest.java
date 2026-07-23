package com.redculture.platform.service.agent;

import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.config.AgentQaFallbackConfig;
import com.redculture.platform.vo.AgentGenerationStatus;
import com.redculture.platform.vo.AgentIntent;
import com.redculture.platform.vo.ai.KnowledgeRetrieveResult;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmAnswerGeneratorTest {

    @Test
    void fallsBackToTemplateWhenLlmServiceIsUnavailable() {
        AppMapProperties properties = new AppMapProperties();
        properties.setLlmServiceBaseUrl("http://127.0.0.1:1");

        AgentAnswerContext context = new AgentAnswerContext();
        context.setQuestion("附近有哪些红色资源？");
        context.setIntent(AgentIntent.NEARBY_RESOURCE);
        context.setRetrieval(KnowledgeRetrieveResult.empty());

        GeneratedAnswer generated = new LlmAnswerGenerator(properties).generate(context);

        assertEquals(AgentGenerationStatus.DEGRADED, generated.getGenerationStatus());
        assertTrue(generated.getAnswer().contains("暂未查询到")
                || generated.getAnswer().contains("无法生成"));
    }

    @Test
    void registersTemplateFallbackAlongsidePrimaryLlmGenerator() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AppMapProperties.class, AppMapProperties::new);
            context.register(LlmAnswerGenerator.class, AgentQaFallbackConfig.class);
            context.refresh();

            assertEquals(2, context.getBeansOfType(AnswerGenerator.class).size());
            assertTrue(context.containsBean("templateAnswerGenerator"));
            assertTrue(context.getBean(AnswerGenerator.class) instanceof LlmAnswerGenerator);
        }
    }
}
