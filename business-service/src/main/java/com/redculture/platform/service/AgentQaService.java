package com.redculture.platform.service;

import com.redculture.platform.vo.AgentQaResponse;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.ai.AssistantConversationTurnCancellation;
import com.redculture.platform.vo.ai.AgentActionVO;
import com.redculture.platform.vo.request.AgentQaRequest;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

public interface AgentQaService {

    Mono<AgentQaResponse> ask(AgentQaRequest request, AuthCurrentUserVO currentUser);

    Flux<ServerSentEvent<Map<String, Object>>> stream(
            AgentQaRequest request, AuthCurrentUserVO currentUser);

    Mono<AssistantConversationTurnCancellation> cancelTurn(
            String clientTurnId, AuthCurrentUserVO currentUser);

    Mono<AgentActionVO> getAction(String actionId, AuthCurrentUserVO currentUser);

    Mono<AgentActionVO> decideAction(
            String actionId, String decision, AuthCurrentUserVO currentUser);
}
