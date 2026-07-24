package com.redculture.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.agent")
public class AgentProperties {

    /**
     * 仅供 llm-service 调用 Java 内部工具接口的共享服务令牌。
     * 本地使用固定开发默认值，部署时必须通过环境变量替换，避免误暴露业务数据。
     */
    private String internalServiceToken = "red-culture-agent-development-token-change-me";

    private int connectTimeoutMs = 3000;

    private int readTimeoutMs = 30000;

    private long streamTimeoutMs = 65000L;

}
