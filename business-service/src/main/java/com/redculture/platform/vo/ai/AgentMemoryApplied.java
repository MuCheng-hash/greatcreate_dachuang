package com.redculture.platform.vo.ai;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentMemoryApplied {

    private int count;

    private List<String> memoryIds = new ArrayList<>();
}
