package com.redculture.platform.vo.request;

import lombok.Data;

/** Agent操作决策请求参数。 */
@Data
public class AgentActionDecisionRequest {

    /** 用户对待确认操作的处理决定，只允许 {@code approve} 或 {@code reject}。 */
    private String decision;
}
