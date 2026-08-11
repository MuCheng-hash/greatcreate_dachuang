package com.redculture.platform.service.agent;

/**
 * 具体下游发布器必须使用 actionId 作为幂等键；不支持幂等的下游不得实现此接口。
 */
public interface AgentOutboxPublisher {

    boolean supports(String eventType);

    void publish(String actionId, String eventType, String payloadJson);
}
