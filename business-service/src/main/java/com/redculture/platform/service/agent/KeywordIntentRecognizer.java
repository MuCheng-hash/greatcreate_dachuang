package com.redculture.platform.service.agent;

import com.redculture.platform.vo.AgentIntent;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class KeywordIntentRecognizer implements IntentRecognizer {

    @Override
    public AgentIntent recognize(String question) {
        String normalized = question == null ? "" : question.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return AgentIntent.UNKNOWN;
        }
        if (containsAny(normalized, "关系", "关联", "联系", "教学关联")) {
            return AgentIntent.RELATION_QUERY;
        }
        if (containsAny(normalized, "附近", "周边", "有哪些资源", "资源推荐", "可用资源")) {
            return AgentIntent.NEARBY_RESOURCE;
        }
        if (containsAny(normalized, "怎样", "如何", "怎么开展", "怎么设计", "怎么利用", "开展", "设计", "课堂", "课程", "活动", "实践", "志愿", "思政课", "教学建议")) {
            return AgentIntent.TEACHING_SUGGESTION;
        }
        if (containsAny(normalized, "介绍", "是什么", "详情", "教育价值", "教育意义", "解释", "适合什么年级")) {
            return AgentIntent.RESOURCE_EXPLANATION;
        }
        return AgentIntent.UNKNOWN;
    }

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
