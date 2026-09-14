package com.redculture.platform.vo.ai;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Agent记忆已应用数据对象。 */
@Data
public class AgentMemoryApplied {

    /** 数量。 */
    private int count;

    /** 本轮实际应用的记忆标识列表。 */
    private List<String> memoryIds = new ArrayList<>();
}
