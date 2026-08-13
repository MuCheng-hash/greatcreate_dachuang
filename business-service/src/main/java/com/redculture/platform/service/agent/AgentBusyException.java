package com.redculture.platform.service.agent;

public class AgentBusyException extends RuntimeException {

    public AgentBusyException(Throwable cause) {
        super("agent_busy", cause);
    }
}
