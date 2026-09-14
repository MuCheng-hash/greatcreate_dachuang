package com.redculture.platform.vo.ai;

import lombok.Data;

/** Agent范围视图对象。 */
@Data
public class AgentScopeVO {

    /**
     * 业务数据的作用范围类型。
     * 该值决定 {@code scopeId} 应解释为学校、区域还是资源等对象。
     */
    private String scopeType;

    /**
     * 业务作用范围标识。
     * 其实际对象类型由 {@code scopeType} 决定。
     */
    private Long scopeId;

    /** 名称。 */
    private String name;
}
