package com.redculture.platform.service.agent;

import com.redculture.platform.vo.AgentIntent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeywordIntentRecognizerTest {

    private final KeywordIntentRecognizer recognizer = new KeywordIntentRecognizer();

    @Test
    void recognizesNearbyResourceQuestion() {
        assertEquals(AgentIntent.NEARBY_RESOURCE,
                recognizer.recognize("某小学附近有哪些适合四年级的红色资源？"));
    }

    @Test
    void recognizesTeachingSuggestionQuestion() {
        assertEquals(AgentIntent.TEACHING_SUGGESTION,
                recognizer.recognize("这所学校可以怎样开展敬老志愿服务？"));
    }

    @Test
    void recognizesRelationQuestionBeforeGenericTeachingKeywords() {
        assertEquals(AgentIntent.RELATION_QUERY,
                recognizer.recognize("某个人物和本地学校有哪些教学关联？"));
    }

    @Test
    void recognizesResourceExplanationQuestion() {
        assertEquals(AgentIntent.RESOURCE_EXPLANATION,
                recognizer.recognize("请介绍这个资源的教育价值。"));
    }

    @Test
    void returnsUnknownForUnsupportedQuestion() {
        assertEquals(AgentIntent.UNKNOWN, recognizer.recognize("今天的天气怎么样？"));
    }
}
