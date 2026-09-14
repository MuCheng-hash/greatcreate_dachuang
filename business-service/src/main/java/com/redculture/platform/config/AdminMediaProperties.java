package com.redculture.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 绑定后台媒体文件的本地存储目录配置。
 */
@Component
@ConfigurationProperties(prefix = "app.admin-media")
public class AdminMediaProperties {

    /**
     * 文件存储目录；对应配置项 {@code app.admin-media.storage-dir}，默认值为 {@code Path.of(System.getProperty("user.dir"), "data", "resource-media").toString()}。
     */
    private String storageDir = Path.of(System.getProperty("user.dir"), "data", "resource-media").toString();

    /**
     * 获取媒体文件存储目录配置。
     *
     * @return 存储目录配置值
     */
    public String getStorageDir() {
        return storageDir;
    }

    /**
     * 设置媒体文件存储目录配置。
     *
     * @param storageDir 存储目录配置值
     */
    public void setStorageDir(String storageDir) {
        this.storageDir = storageDir;
    }

    /**
     * 解析并规范化媒体文件存储目录。
     *
     * @return 规范化后的绝对存储路径
     */
    public Path storagePath() {
        return Path.of(storageDir).toAbsolutePath().normalize();
    }
}
