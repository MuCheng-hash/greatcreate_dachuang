package com.redculture.platform.vo.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Agent记忆设置更新请求参数。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentMemorySettingUpdateRequest {

    /** 是否启用。 */
    private Boolean enabled;
}
