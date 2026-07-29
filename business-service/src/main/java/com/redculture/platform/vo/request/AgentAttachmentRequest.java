package com.redculture.platform.vo.request;

import lombok.Data;

@Data
public class AgentAttachmentRequest {

    private String type;

    private String name;

    private String mediaType;

    private String dataUrl;
}
