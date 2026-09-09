package com.redculture.platform.vo.ai;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** 助手会话消息数据对象。 */
@Data
public class AssistantConversationMessage {
    /** 唯一标识。 */
    private Long id;
    /** 角色。 */
    private String role;
    /** 内容。 */
    private String content;
    /** 创建时间。 */
    private String createdAt;
    /**
     * 附加元数据。
     * 仅承载协议约定的扩展信息，不作为主要业务内容。
     */
    private Map<String, Object> metadata = new LinkedHashMap<>();
}
