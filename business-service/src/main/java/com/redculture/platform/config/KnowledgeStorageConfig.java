package com.redculture.platform.config;

import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(KnowledgeStorageProperties.class)
public class KnowledgeStorageConfig {
    @Bean
    MinioClient knowledgeMinioClient(KnowledgeStorageProperties properties) {
        return MinioClient.builder().endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey()).build();
    }
}
