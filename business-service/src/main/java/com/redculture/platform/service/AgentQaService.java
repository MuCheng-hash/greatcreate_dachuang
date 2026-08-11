package com.redculture.platform.service;

import com.redculture.platform.vo.AgentQaResponse;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.ai.AssistantConversationTurnCancellation;
import com.redculture.platform.vo.ai.AgentActionVO;
import com.redculture.platform.vo.request.AgentQaRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface AgentQaService {

    AgentQaResponse ask(AgentQaRequest request, AuthCurrentUserVO currentUser);

    SseEmitter stream(AgentQaRequest request, AuthCurrentUserVO currentUser);

    AssistantConversationTurnCancellation cancelTurn(
            String clientTurnId, AuthCurrentUserVO currentUser);

    AgentActionVO getAction(String actionId, AuthCurrentUserVO currentUser);

    AgentActionVO decideAction(
            String actionId, String decision, AuthCurrentUserVO currentUser);
}
