package com.redculture.platform.vo.ai;

import lombok.Data;

/** Agent执行主体视图对象。 */
@Data
public class AgentActorVO {

    /** 账号标识。 */
    private Long accountId;

    /** 角色编码。 */
    private String roleCode;

    /** 学校标识。 */
    private Long schoolId;
}
