package com.redculture.platform.config;

import com.redculture.platform.service.KnowledgeRetriever;
import com.redculture.platform.service.agent.TemplateAnswerGenerator;
import com.redculture.platform.service.impl.MockKnowledgeRetriever;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 在正式 RAG 与答案生成实现尚未配置时，提供可在本地运行的回退实现。
 */
@Configuration
public class AgentQaFallbackConfig {

    /**
     * 创建本地知识检索回退实现。
     *
     * @return 知识检索回退实现
     */
    @Bean
    @ConditionalOnMissingBean(KnowledgeRetriever.class)
    public KnowledgeRetriever knowledgeRetriever() {
        return new MockKnowledgeRetriever();
    }

    /**
     * 创建模板答案生成回退实现。
     *
     * @return 模板答案生成回退实现
     */
    @Bean
    @ConditionalOnMissingBean(TemplateAnswerGenerator.class)
    public TemplateAnswerGenerator templateAnswerGenerator() {
        return new TemplateAnswerGenerator();
    }
}
