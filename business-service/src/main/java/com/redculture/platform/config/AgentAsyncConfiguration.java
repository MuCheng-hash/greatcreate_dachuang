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

@Configuration
public class AgentAsyncConfiguration implements WebMvcConfigurer {

    private final AgentProperties properties;

    public AgentAsyncConfiguration(AgentProperties properties) {
        this.properties = properties;
    }

    @Bean("agentWebClient")
    public WebClient agentWebClient(AppMapProperties mapProperties) {
        return createAgentWebClient(mapProperties, properties);
    }

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

    @Bean(name = "agentBlockingScheduler", destroyMethod = "dispose")
    public Scheduler agentBlockingScheduler(
            @Qualifier("agentBlockingExecutor") ExecutorService executor) {
        return Schedulers.fromExecutorService(executor);
    }

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

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        AsyncTaskExecutor executor = applicationTaskExecutor();
        configurer.setTaskExecutor(executor);
        configurer.setDefaultTimeout(0L);
    }
}
