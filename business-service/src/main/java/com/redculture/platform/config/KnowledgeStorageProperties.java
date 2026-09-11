package com.redculture.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定知识文件对象存储连接和上传限制配置。
 */
@Data
@ConfigurationProperties(prefix = "app.knowledge-storage")
public class KnowledgeStorageProperties {
    /**
     * 对象存储服务地址；对应配置项 {@code app.knowledge-storage.endpoint}，默认值为 {@code "http://127.0.0.1:9000"}。
     */
    private String endpoint = "http://127.0.0.1:9000";
    /**
     * 对象存储访问密钥；对应配置项 {@code app.knowledge-storage.access-key}，默认值为 {@code "minioadmin"}。
     */
    private String accessKey = "minioadmin";
    /**
     * 对象存储私密密钥；对应配置项 {@code app.knowledge-storage.secret-key}，默认值为 {@code "minioadmin"}。
     */
    private String secretKey = "minioadmin";
    /**
     * 知识文件存储桶名称；对应配置项 {@code app.knowledge-storage.bucket}，默认值为 {@code "knowledge"}。
     */
    private String bucket = "knowledge";
    /**
     * 允许上传的单个知识文件最大字节数；对应配置项 {@code app.knowledge-storage.max-file-size-bytes}，默认值为 {@code 50L * 1024 * 1024}。
     */
    private long maxFileSizeBytes = 50L * 1024 * 1024;
}
