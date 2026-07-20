package com.redculture.platform.service.agent;

import com.redculture.platform.vo.AgentIntent;

public interface IntentRecognizer {

    AgentIntent recognize(String question);
}
