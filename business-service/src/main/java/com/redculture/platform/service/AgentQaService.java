package com.redculture.platform.service;

import com.redculture.platform.vo.AgentQaResponse;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.request.AgentQaRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

public interface AgentQaService {

    AgentQaResponse ask(AgentQaRequest request, AuthCurrentUserVO currentUser);

    Map<String, Object> getThreadHistory(String threadId,
                                         AuthCurrentUserVO currentUser,
                                         String scopeType,
                                         Long scopeId);

    SseEmitter stream(AgentQaRequest request, AuthCurrentUserVO currentUser);
}
