package com.redculture.platform.vo.ai;

import lombok.Data;

/** Agent记忆设置数据。 */
@Data
public class AgentMemorySetting {

    /** 当前对象是否可用。 */
    private boolean available;

    /** 是否启用。 */
    private boolean enabled;

    /** 综合全局与用户设置后是否实际启用。 */
    private boolean effectiveEnabled;

    /** 创建时间。 */
    private String createdAt;

    /** 最后更新时间。 */
    private String updatedAt;
}
