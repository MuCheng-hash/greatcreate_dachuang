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

    /**
     * stateful 为默认运行时，legacy 仅用于旧 Agent 链路回滚。
     */
    private String runtimeMode = "stateful";

    private int connectTimeoutMs = 3000;

    private int readTimeoutMs = 30000;

    private long streamTimeoutMs = 65000L;

    public boolean isLegacyRuntime() {
        return "legacy".equalsIgnoreCase(runtimeMode);
    }
}
