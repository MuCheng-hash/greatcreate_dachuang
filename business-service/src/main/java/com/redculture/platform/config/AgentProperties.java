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

    /** MySQL/Neo4j 等遗留阻塞上下文任务的最大并发线程数。 */
    private int blockingMaxThreads = 8;

    /** 遗留阻塞上下文任务的总等待队列容量。 */
    private int blockingQueueCapacity = 64;

    /** Spring MVC 异步响应写入执行器。 */
    private int mvcAsyncCoreThreads = 4;

    private int mvcAsyncMaxThreads = 16;

    private int mvcAsyncQueueCapacity = 128;

    /** 写工具总开关。完成数据库迁移和具体工具安全评审前必须保持关闭。 */
    private boolean writeToolsEnabled = false;

    private int outboxBatchSize = 20;

    private int outboxLeaseSeconds = 30;

    private int outboxRetrySeconds = 15;

    private int actionPayloadRetentionDays = 30;

}
