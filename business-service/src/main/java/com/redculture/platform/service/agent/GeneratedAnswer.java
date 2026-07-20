package com.redculture.platform.service.agent;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@AllArgsConstructor
public class GeneratedAnswer {

    private String answer;

    private List<String> citationIds = new ArrayList<>();

    private List<String> followUpQuestions = new ArrayList<>();
}
