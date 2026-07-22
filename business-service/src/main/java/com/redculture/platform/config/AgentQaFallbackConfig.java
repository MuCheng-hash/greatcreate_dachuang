package com.redculture.platform.config;

import com.redculture.platform.service.KnowledgeRetriever;
import com.redculture.platform.service.agent.AnswerGenerator;
import com.redculture.platform.service.agent.TemplateAnswerGenerator;
import com.redculture.platform.service.impl.MockKnowledgeRetriever;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides local fallbacks until real RAG and answer-generation implementations are configured.
 */
@Configuration
public class AgentQaFallbackConfig {

    @Bean
    @ConditionalOnMissingBean(KnowledgeRetriever.class)
    public KnowledgeRetriever knowledgeRetriever() {
        return new MockKnowledgeRetriever();
    }

    @Bean
    @ConditionalOnMissingBean(AnswerGenerator.class)
    public AnswerGenerator answerGenerator() {
        return new TemplateAnswerGenerator();
    }
}
