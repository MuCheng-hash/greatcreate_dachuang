package com.redculture.platform.vo.ai;

import lombok.Data;

@Data
public class AssistantConversationTurnRecovery {

    private boolean found;

    private String threadId;

    private String clientTurnId;

    private String turnStatus;

    private boolean retryable;

    private AssistantConversationMessage partialMessage;

    private AssistantConversationMessage message;
}
