package com.redculture.platform.vo.ai;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Agent记忆冲突预览数据对象。 */
@Data
public class AgentMemoryConflictPreview {

    /** 候选。 */
    private AgentMemoryItem candidate;

    /** 冲突列表。 */
    private List<AgentMemoryItem> conflicts = new ArrayList<>();

    /** 当前数据是否为重复记录。 */
    private boolean duplicate;
}
