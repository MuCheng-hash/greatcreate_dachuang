package com.redculture.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定 Agent 调用、异步执行和写动作基础设施的配置项。
 */
@Data
@ConfigurationProperties(prefix = "app.agent")
public class AgentProperties {

    /**
     * 仅供 llm-service 调用 Java 内部工具接口的共享服务令牌。
     * 必须通过 AGENT_INTERNAL_SERVICE_TOKEN 注入，缺失时内部工具保持拒绝访问。
     */
    private String internalServiceToken = "";

    /**
     * Java 签发并验证动态工具上下文的 HMAC 密钥。
     * 缺失时仍允许初始 RAG，但所有动态工具调用必须降级拒绝。
     */
    private String toolContextSigningSecret = "";

    /** 动态工具授权凭据的有效期，单位秒。 */
    private int toolContextAuthorizationTtlSeconds = 120;

    /** FastAPI Prompt 管理令牌，仅由 Java 服务端代理使用。 */
    private String promptAdminToken;

    /** FastAPI Observability 管理令牌，仅由 Java 服务端代理使用。 */
    private String observabilityAdminToken;

    /**
     * 建立连接的超时时间，单位毫秒；对应配置项 {@code app.agent.connect-timeout-ms}，默认值为 {@code 3000}。
     */
    private int connectTimeoutMs = 3000;

    /**
     * 读取响应的超时时间，单位毫秒；对应配置项 {@code app.agent.read-timeout-ms}，默认值为 {@code 30000}。
     */
    private int readTimeoutMs = 30000;

    /**
     * 流式响应的最长等待时间，单位毫秒；对应配置项 {@code app.agent.stream-timeout-ms}，默认值为 {@code 65000L}。
     */
    private long streamTimeoutMs = 65000L;

    /** MySQL/Neo4j 等遗留阻塞上下文任务的最大并发线程数。 */
    private int blockingMaxThreads = 8;

    /** 遗留阻塞上下文任务的总等待队列容量。 */
    private int blockingQueueCapacity = 64;

    /** Spring MVC 异步响应写入执行器。 */
    private int mvcAsyncCoreThreads = 4;

    /**
     * Spring MVC 异步执行器的最大线程数；对应配置项 {@code app.agent.mvc-async-max-threads}，默认值为 {@code 16}。
     */
    private int mvcAsyncMaxThreads = 16;

    /**
     * Spring MVC 异步执行器的等待队列容量；对应配置项 {@code app.agent.mvc-async-queue-capacity}，默认值为 {@code 128}。
     */
    private int mvcAsyncQueueCapacity = 128;

    /** 写工具总开关。完成数据库迁移和具体工具安全评审前必须保持关闭。 */
    private boolean writeToolsEnabled = false;

    /**
     * 单次领取的发件箱事件数量；对应配置项 {@code app.agent.outbox-batch-size}，默认值为 {@code 20}。
     */
    private int outboxBatchSize = 20;

    /**
     * 发件箱事件处理租约时长，单位秒；对应配置项 {@code app.agent.outbox-lease-seconds}，默认值为 {@code 30}。
     */
    private int outboxLeaseSeconds = 30;

    /**
     * 发件箱发布失败后的重试间隔，单位秒；对应配置项 {@code app.agent.outbox-retry-seconds}，默认值为 {@code 15}。
     */
    private int outboxRetrySeconds = 15;

    /**
     * Agent 动作载荷的保留天数；对应配置项 {@code app.agent.action-payload-retention-days}，默认值为 {@code 30}。
     */
    private int actionPayloadRetentionDays = 30;

}
