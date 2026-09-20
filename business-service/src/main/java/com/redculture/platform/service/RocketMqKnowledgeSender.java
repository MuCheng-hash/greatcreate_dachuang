package com.redculture.platform.service;

import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Component
public class RocketMqKnowledgeSender implements KnowledgeMessageSender {
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final String endpoints;
    private final String topic;
    private final Duration requestTimeout;
    private Producer producer;

    public RocketMqKnowledgeSender(@Value("${app.knowledge-mq.endpoints:localhost:18081}") String endpoints,
            @Value("${app.knowledge-mq.topic:knowledge-ingest}") String topic,
            @Value("${app.knowledge-mq.request-timeout-seconds:10}") long requestTimeoutSeconds) {
        this.endpoints = endpoints;
        this.topic = topic;
        if (requestTimeoutSeconds <= 0) throw new IllegalArgumentException("MQ 请求超时时间必须为正数");
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
    }

    @Override public synchronized void send(String eventId, byte[] body) throws Exception {
        if (producer == null) {
            producer = provider.newProducerBuilder().setClientConfiguration(ClientConfiguration.newBuilder()
                    .setEndpoints(endpoints).enableSsl(false).setRequestTimeout(requestTimeout).build())
                    .setTopics(topic).build();
        }
        producer.send(provider.newMessageBuilder().setTopic(topic).setKeys(eventId).setBody(body).build());
    }

    @PreDestroy public synchronized void close() throws Exception {
        if (producer != null) producer.close();
    }
}
