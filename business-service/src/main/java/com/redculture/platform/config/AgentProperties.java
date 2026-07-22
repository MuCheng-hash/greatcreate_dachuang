package com.redculture.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.agent")
public class AgentProperties {

    /**
     * 仅供 llm-service 调用 Java 内部工具接口的共享服务令牌。
     * 为空时内部接口保持关闭，避免误暴露业务数据。
     */
    private String internalServiceToken;

    private int connectTimeoutMs = 3000;

    private int readTimeoutMs = 30000;

    private long streamTimeoutMs = 65000L;
}
