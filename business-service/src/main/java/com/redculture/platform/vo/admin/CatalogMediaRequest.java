package com.redculture.platform.vo.admin;

import lombok.Data;

/** 目录媒体请求参数。 */
@Data
public class CatalogMediaRequest {
    /** 媒体标识。 */
    private Long mediaId;
    /** 媒体地址。 */
    private String mediaUrl;
    /** 封面地址。 */
    private String coverUrl;
    /** 媒体标题。 */
    private String mediaTitle;
    /** 媒体 MIME 类型。 */
    private String mediaType;
    /** 描述信息。 */
    private String description;
    /** 版权说明。 */
    private String copyrightNote;
    /** 是否为主要项。 */
    private Boolean primary;
}
