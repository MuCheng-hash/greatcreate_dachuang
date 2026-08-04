package com.redculture.platform.vo.ai;

import lombok.Data;

@Data
public class AssistantConversationTurnRecovery {

    private boolean found;

    private String threadId;

    private AssistantConversationMessage message;
}
