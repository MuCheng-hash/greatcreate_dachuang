package com.redculture.platform.vo.request;

import lombok.Data;

/** Agent附件请求参数。 */
@Data
public class AgentAttachmentRequest {

    /**
     * 当前对象的类型标识。
     * 具体取值由对应业务协议定义。
     */
    private String type;

    /** 名称。 */
    private String name;

    /** 媒体 MIME 类型。 */
    private String mediaType;

    /**
     * 附件的 Data URL。
     * 内容通常由媒体类型和 Base64 编码数据组成。
     */
    private String dataUrl;
}
