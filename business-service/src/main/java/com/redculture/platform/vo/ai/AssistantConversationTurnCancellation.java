package com.redculture.platform.vo.ai;

import lombok.Data;

@Data
public class AssistantConversationTurnCancellation {

    private String clientTurnId;

    private String threadId;

    private String turnStatus;

    private boolean cancellationRequested;
}
