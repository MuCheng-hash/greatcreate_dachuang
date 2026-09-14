package com.redculture.platform.config;

import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 启用知识文件对象存储配置属性。
 */
@Configuration
@EnableConfigurationProperties(KnowledgeStorageProperties.class)
public class KnowledgeStorageConfig {
    @Bean
    MinioClient knowledgeMinioClient(KnowledgeStorageProperties properties) {
        return MinioClient.builder().endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey()).build();
    }
}
