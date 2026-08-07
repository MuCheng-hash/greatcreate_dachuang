package com.redculture.platform.vo.admin;

import lombok.Data;

@Data
public class CatalogMediaRequest {
    private String mediaUrl;
    private String coverUrl;
    private String mediaTitle;
    private String mediaType;
    private String description;
    private String copyrightNote;
    private Boolean primary;
}
