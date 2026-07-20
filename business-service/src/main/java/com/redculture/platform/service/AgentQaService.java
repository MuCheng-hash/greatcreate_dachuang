package com.redculture.platform.service;

import com.redculture.platform.vo.AgentQaResponse;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.request.AgentQaRequest;

public interface AgentQaService {

    AgentQaResponse ask(AgentQaRequest request, AuthCurrentUserVO currentUser);
}
