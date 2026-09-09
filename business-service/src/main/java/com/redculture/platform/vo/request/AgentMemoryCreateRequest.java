package com.redculture.platform.vo.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Agent记忆创建请求参数。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentMemoryCreateRequest {

    /** 记忆类型，用于区分稳定画像与阶段性任务等内容。 */
    private String memoryType;

    /** 结构化记忆字段键；自定义记忆可为空。 */
    private String fieldKey;

    /** 内容。 */
    private String content;

    /** 保存新记忆时是否替换同一字段下的冲突记忆。 */
    private Boolean replaceConflicts;

    /**
     * 创建不主动替换冲突项的记忆请求。
     *
     * @param memoryType 记忆类型
     * @param fieldKey 结构化记忆字段键
     * @param content 记忆内容
     */
    public AgentMemoryCreateRequest(String memoryType, String fieldKey, String content) {
        this(memoryType, fieldKey, content, null);
    }
}
