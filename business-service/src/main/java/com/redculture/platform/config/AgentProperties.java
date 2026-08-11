package com.redculture.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.agent")
public class AgentProperties {

    /**
     * 仅供 llm-service 调用 Java 内部工具接口的共享服务令牌。
     * 必须通过 AGENT_INTERNAL_SERVICE_TOKEN 注入，缺失时内部工具保持拒绝访问。
     */
    private String internalServiceToken = "";

    /** FastAPI Prompt 管理令牌，仅由 Java 服务端代理使用。 */
    private String promptAdminToken;

    /** FastAPI Observability 管理令牌，仅由 Java 服务端代理使用。 */
    private String observabilityAdminToken;

    private int connectTimeoutMs = 3000;

    private int readTimeoutMs = 30000;

    private long streamTimeoutMs = 65000L;

    /** 写工具总开关。完成数据库迁移和具体工具安全评审前必须保持关闭。 */
    private boolean writeToolsEnabled = false;

    private int outboxBatchSize = 20;

    private int outboxLeaseSeconds = 30;

    private int outboxRetrySeconds = 15;

    private int actionPayloadRetentionDays = 30;

}
