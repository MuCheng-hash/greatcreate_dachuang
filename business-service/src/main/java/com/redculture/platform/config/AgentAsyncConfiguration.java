package com.redculture.platform.config;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 配置 Agent HTTP 客户端、阻塞任务线程池及 Spring MVC 异步执行器。
 */
@Configuration
public class AgentAsyncConfiguration implements WebMvcConfigurer {

    /**
     * 当前组件使用的配置属性。
     */
    private final AgentProperties properties;

    /**
     * 创建 Agent 异步执行配置。
     *
     * @param properties 相关配置属性
     */
    public AgentAsyncConfiguration(AgentProperties properties) {
        this.properties = properties;
    }

    /**
     * 创建供 Agent 调用 LLM 服务的 HTTP 客户端。
     *
     * @param mapProperties 地图与服务地址配置
     * @return 配置完成的 HTTP 客户端
     */
    @Bean("agentWebClient")
    public WebClient agentWebClient(AppMapProperties mapProperties) {
        return createAgentWebClient(mapProperties, properties);
    }

    /**
     * 按服务地址和超时参数创建 Agent HTTP 客户端。
     *
     * @param mapProperties 地图与服务地址配置
     * @param properties 相关配置属性
     * @return 配置完成的 HTTP 客户端
     */
    public static WebClient createAgentWebClient(AppMapProperties mapProperties,
                                                  AgentProperties properties) {
        HttpClient httpClient = HttpClient.create().option(
                ChannelOption.CONNECT_TIMEOUT_MILLIS,
                Math.max(1, properties.getConnectTimeoutMs())
        );
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(mapProperties.getLlmServiceBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient));
        if (StringUtils.hasText(properties.getInternalServiceToken())) {
            builder.defaultHeader(
                    "X-Agent-Service-Token",
                    properties.getInternalServiceToken()
            );
        }
        return builder.build();
    }

    /**
     * 创建执行遗留阻塞任务的线程池。
     *
     * @return 阻塞任务执行器
     */
    @Bean(name = "agentBlockingExecutor", destroyMethod = "shutdown")
    public ExecutorService agentBlockingExecutor() {
        int maxThreads = Math.max(1, properties.getBlockingMaxThreads());
        int queueCapacity = Math.max(1, properties.getBlockingQueueCapacity());
        return new ThreadPoolExecutor(
                maxThreads,
                maxThreads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform().name("agent-blocking-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * 基于阻塞任务线程池创建 Reactor 调度器。
     *
     * @param executor Agent 阻塞任务执行器
     * @return 阻塞任务调度器
     */
    @Bean(name = "agentBlockingScheduler", destroyMethod = "dispose")
    public Scheduler agentBlockingScheduler(
            @Qualifier("agentBlockingExecutor") ExecutorService executor) {
        return Schedulers.fromExecutorService(executor);
    }

    /**
     * 创建 Spring MVC 异步请求执行器。
     *
     * @return Spring MVC 异步任务执行器
     */
    @Bean(name = "applicationTaskExecutor")
    public ThreadPoolTaskExecutor applicationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("mvc-agent-write-");
        executor.setCorePoolSize(Math.max(1, properties.getMvcAsyncCoreThreads()));
        executor.setMaxPoolSize(Math.max(
                executor.getCorePoolSize(),
                properties.getMvcAsyncMaxThreads()
        ));
        executor.setQueueCapacity(Math.max(1, properties.getMvcAsyncQueueCapacity()));
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 设置 Spring MVC 异步请求执行器和默认超时。
     *
     * @param configurer Spring MVC 异步支持配置器
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        AsyncTaskExecutor executor = applicationTaskExecutor();
        configurer.setTaskExecutor(executor);
        configurer.setDefaultTimeout(0L);
    }
}
