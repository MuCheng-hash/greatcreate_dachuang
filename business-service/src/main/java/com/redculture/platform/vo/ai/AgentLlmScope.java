package com.redculture.platform.vo.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentLlmScope {

    private KnowledgeScopeType scopeType;

    private Long scopeId;

    private String name;
}
