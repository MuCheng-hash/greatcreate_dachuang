package com.redculture.platform.service.agent;

import com.redculture.platform.vo.AgentGenerationStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GeneratedAnswer {

    private String answer;

    private List<String> citationIds = new ArrayList<>();

    private List<String> followUpQuestions = new ArrayList<>();

    private AgentGenerationStatus generationStatus = AgentGenerationStatus.COMPLETED;

    public GeneratedAnswer(String answer,
                           List<String> citationIds,
                           List<String> followUpQuestions) {
        this(answer, citationIds, followUpQuestions, AgentGenerationStatus.COMPLETED);
    }
}
