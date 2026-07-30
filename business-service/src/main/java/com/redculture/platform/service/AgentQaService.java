package com.redculture.platform.service;

import com.redculture.platform.vo.AgentQaResponse;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.request.AgentQaRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface AgentQaService {

    AgentQaResponse ask(AgentQaRequest request, AuthCurrentUserVO currentUser);

    SseEmitter stream(AgentQaRequest request, AuthCurrentUserVO currentUser);
}
