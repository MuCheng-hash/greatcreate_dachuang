package com.redculture.platform.vo.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Agent记忆处理请求参数。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentMemoryResolutionRequest {

    /** 确认候选记忆时是否替换同一字段下的冲突记忆。 */
    private Boolean replaceConflicts;
}
