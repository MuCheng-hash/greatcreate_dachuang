package com.redculture.platform.service.agent;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@AllArgsConstructor
public class AgentRuntimeResult {

    private GeneratedAnswer answer;

    private String threadId;

    private String status;

    private List<String> toolExecutions = new ArrayList<>();
}
