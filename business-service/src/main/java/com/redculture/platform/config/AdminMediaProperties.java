package com.redculture.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@ConfigurationProperties(prefix = "app.admin-media")
public class AdminMediaProperties {

    private String storageDir = Path.of(System.getProperty("user.dir"), "data", "resource-media").toString();

    public String getStorageDir() {
        return storageDir;
    }

    public void setStorageDir(String storageDir) {
        this.storageDir = storageDir;
    }

    public Path storagePath() {
        return Path.of(storageDir).toAbsolutePath().normalize();
    }
}
