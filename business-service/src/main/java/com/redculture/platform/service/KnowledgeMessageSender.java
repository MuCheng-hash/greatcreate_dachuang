package com.redculture.platform.service;

public interface KnowledgeMessageSender {
    void send(String eventId, byte[] body) throws Exception;
}
