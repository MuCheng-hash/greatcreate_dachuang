package com.redculture.platform.vo.ai;

import com.redculture.platform.vo.AgentGenerationStatus;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentLlmResponse {

    private String answer;

    private List<String> citationIds = new ArrayList<>();

    private List<String> followUpQuestions = new ArrayList<>();

    private AgentGenerationStatus generationStatus;

    private String message;
}
